package controllers

import config.BackendConfig
import modules.StackverseModule
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import repositories.Db

import java.nio.file.Paths

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

    "compile the public API routes against focused controllers" in {
      route(app, FakeRequest(GET, "/api/v1/bookmarks")).isDefined.shouldBe(true)
      route(app, FakeRequest(GET, "/api/v1/messages")).isDefined.shouldBe(true)
      route(app, FakeRequest(GET, "/api/v1/admin/stats")).isDefined.shouldBe(true)
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
