package controllers

import config.BackendConfig
import models.{BadRequestProblem, ForbiddenProblem}
import modules.StackverseModule
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsString, JsValue, Json}
import play.api.mvc.{AnyContent, BodyParsers, Request, RequestHeader, Results}
import play.api.routing.Router
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import repositories.Db
import services.{ApiAction, AuthService, CallerRequest, DatabaseExecutionContext, EventLogger, I18n}

import java.nio.file.Paths
import java.sql.SQLException

class ApplicationSpec extends AnyWordSpec with Matchers with GuiceOneAppPerSuite with BeforeAndAfterAll {

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .disable[StackverseModule]
      .overrides(bind[BackendConfig].toInstance(testConfig))
      .build()

  "the Play application" should {
    "route liveness through the focused health controller" in {
      val response = route(app, FakeRequest(GET, "/healthz")).get

      status(response).shouldBe(OK)
      contentAsJson(response).shouldBe(Json.obj("status" -> "up"))
    }

    "wire focused API routes without executing database-backed actions" in {
      val routes = app.injector.instanceOf[Router].documentation.map { case (method, path, _) => method -> path }.toSet

      routes.should(contain("GET" -> "/api/v1/bookmarks"))
      routes.should(contain("GET" -> "/api/v1/messages"))
      routes.should(contain("GET" -> "/api/v1/admin/stats"))
      routes.shouldNot(contain("GET" -> "/api/v1/not-a-route"))
    }

    "keep feature actions in focused controllers without a residual god service" in {
      val controllers = Seq(
        classOf[IdentityController],
        classOf[BookmarkController],
        classOf[MessageController],
        classOf[ModerationController],
        classOf[AdminController]
      )

      controllers.foreach { controller =>
        val dependencies = controller.getConstructors.flatMap(_.getParameterTypes)
        dependencies.should(contain(classOf[ApiAction]))
        dependencies.map(_.getName).exists(_.endsWith("StackverseActions")).shouldBe(false)
      }
      a[ClassNotFoundException].shouldBe(thrownBy(Class.forName("services.StackverseActions")))
    }

    "translate controller failures through the Play API action boundary" in {
      given Materializer = app.materializer
      val api = app.injector.instanceOf[ApiAction]
      val action = api((_: Request[AnyContent]) => throw new BadRequestProblem("synthetic boundary failure"))
      val response = call(action, FakeRequest(GET, "/test-boundary"))

      status(response).shouldBe(BAD_REQUEST)
      contentType(response).shouldBe(Some("application/problem+json"))
      (contentAsJson(response) \ "title").as[String].shouldBe("Bad Request")
      (contentAsJson(response) \ "detail").as[String].shouldBe("synthetic boundary failure")
    }

    "translate unexpected non-fatal failures through the API action boundary" in {
      given Materializer = app.materializer
      val api = app.injector.instanceOf[ApiAction]
      val action = api((_: Request[AnyContent]) => throw new IllegalStateException("synthetic unexpected failure"))
      val response = call(action, FakeRequest(GET, "/test-boundary"))

      status(response).shouldBe(INTERNAL_SERVER_ERROR)
      contentType(response).shouldBe(Some("application/problem+json"))
      (contentAsJson(response) \ "title").as[String].shouldBe("Internal Server Error")
    }

    "translate database failures raised while localizing an API problem through the terminal boundary" in {
      given Materializer = app.materializer
      var loggedEvent = Option.empty[(String, Option[String], Option[String], Seq[(String, JsValue)])]
      val logger = new EventLogger(testConfig) {
        override protected def write(
            level: String,
            message: String,
            eventName: Option[String],
            outcome: Option[String],
            fields: (String, JsValue)*
        ): Unit = loggedEvent = Some((level, eventName, outcome, fields))
      }
      val i18n = new I18n(app.injector.instanceOf[Db]) {
        override def resolve(request: RequestHeader): String =
          throw new SQLException("synthetic localization dependency failure", "08006")
      }
      val api = new ApiAction(
        app.injector.instanceOf[BodyParsers.Default],
        app.injector.instanceOf[AuthService],
        i18n,
        logger,
        app.injector.instanceOf[DatabaseExecutionContext]
      )
      val action = api((_: Request[AnyContent]) =>
        throw new ForbiddenProblem("localized detail unavailable", Some("error.account.blocked"))
      )
      val response = call(action, FakeRequest(GET, "/test-localization-failure"))

      status(response).shouldBe(INTERNAL_SERVER_ERROR)
      contentType(response).shouldBe(Some("application/problem+json"))
      (contentAsJson(response) \ "title").as[String].shouldBe("Internal Server Error")
      (contentAsJson(response) \ "detail").as[String].shouldBe("An unexpected error occurred.")

      loggedEvent.shouldBe(defined)
      val (level, eventName, outcome, fields) = loggedEvent.get
      val fieldMap = fields.toMap
      level.shouldBe("error")
      eventName.shouldBe(Some("dependency_call_failed"))
      outcome.shouldBe(Some("failure"))
      fieldMap("dependency").shouldBe(JsString("postgres"))
      fieldMap("error_code").shouldBe(JsString("08006"))
      fieldMap("duration_ms").as[Long].should(be >= 0L)
      fieldMap("stack_trace").as[String].should(include("synthetic localization dependency failure"))
    }

    "reject missing callers in composed authenticated actions before controller execution" in {
      given Materializer = app.materializer
      val api = app.injector.instanceOf[ApiAction]
      var executed = false
      val action = api.authenticated { (_: CallerRequest[AnyContent]) =>
        executed = true
        Results.Ok
      }
      val response = call(action, FakeRequest(GET, "/test-authenticated"))

      status(response).shouldBe(UNAUTHORIZED)
      executed.shouldBe(false)
    }

    "offload controller blocks through the database dispatcher" in {
      given Materializer = app.materializer
      val api = app.injector.instanceOf[ApiAction]
      val action = api((_: Request[AnyContent]) => Results.Ok(Json.obj("thread" -> Thread.currentThread().getName)))
      val response = call(action, FakeRequest(GET, "/test-dispatcher"))

      (contentAsJson(response) \ "thread").as[String].should(include("database-dispatcher"))
    }
  }

  override protected def afterAll(): Unit =
    try app.injector.instanceOf[Db].close()
    finally super.afterAll()

  private val testConfig = BackendConfig(
    port = 8080,
    dbHost = "127.0.0.1",
    dbPort = 1,
    dbName = "stackverse",
    dbUser = "stackverse",
    dbPassword = "stackverse",
    oidcIssuerUri = "http://localhost/realms/stackverse",
    oidcJwksUri = None,
    seedMessagesDir = Paths.get("../../spec/messages"),
    logLevel = "error",
    logFormat = "json",
    otelEnabled = false
  )
}
