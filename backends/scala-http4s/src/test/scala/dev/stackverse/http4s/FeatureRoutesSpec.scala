package dev.stackverse.http4s

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import org.http4s.implicits.*
import org.http4s.{Header, HttpRoutes, Method, Request, Response, Status, Uri}
import org.scalatest.funsuite.AnyFunSuite
import org.typelevel.ci.CIString

import scala.collection.mutable.ListBuffer

class FeatureRoutesSpec extends AnyFunSuite {
  private val caller = Caller("demo", Seq("admin", "moderator"), None, None)
  private val handler = new ApiHandler(
    new ProblemLocalization {
      override def resolve(queryLang: Option[String], acceptLanguage: Option[String]): String = "en"
      override def localize(key: String, language: String): String = key
    },
    new ProblemEvents {
      override def event(
          level: String,
          event: String,
          outcome: String,
          message: String,
          fields: (String, Json)*
      ): Unit = ()
    }
  )
  private val security = new RouteSecurity(_ => IO.pure(Some(caller)), handler)

  test("health route executes its service behavior") {
    val service = new HealthOperations {
      override def healthz: IO[Response[IO]] = response("health")
      override def readyz: IO[Response[IO]] = response("ready")
    }

    assertBody(new HealthRoutes(service, handler).routes, request(Method.GET, "/healthz"), "health")
  }

  test("feature routes execute public and authenticated service behavior") {
    val identity = new IdentityAdapter {
      override def me(caller: Caller): IO[Response[IO]] = response("identity")
    }
    val bookmarks = new BookmarkAdapter {
      override def listBookmarksV1(req: Request[IO], caller: Option[Caller]): IO[Response[IO]] = response("bookmarks")
    }
    val messages = new MessageAdapter {
      override def createMessage(req: Request[IO], caller: Caller): IO[Response[IO]] = response("message-created")
    }
    val moderation = new ModerationAdapter {
      override def listMyReports(req: Request[IO], caller: Caller): IO[Response[IO]] = response("reports")
    }
    val admin = new AdminAdapter {
      override def listUsers(req: Request[IO], caller: Caller): IO[Response[IO]] = response("users")
    }

    val cases = Seq(
      (security(new IdentityRoutes(identity, handler).routes), request(Method.GET, "/api/v1/me"), "identity"),
      (security(new BookmarkRoutes(bookmarks, handler).routes), request(Method.GET, "/api/v1/bookmarks"), "bookmarks"),
      (
        security(new MessageRoutes(messages, handler).routes),
        request(Method.POST, "/api/v1/messages"),
        "message-created"
      ),
      (security(new ModerationRoutes(moderation, handler).routes), request(Method.GET, "/api/v1/reports"), "reports"),
      (security(new AdminRoutes(admin, handler).routes), request(Method.GET, "/api/v1/admin/users"), "users")
    )

    cases.foreach(assertBody.tupled)
  }

  test("Kleisli authentication middleware rejects secured routes before service execution") {
    val denied = new RouteSecurity(_ => IO.raiseError(UnauthorizedProblem()), handler)
    val routes = denied(new IdentityRoutes(new IdentityAdapter {}, handler).routes)
    val result = routes.orNotFound.run(request(Method.GET, "/api/v1/me")).unsafeRunSync()

    assert(result.status == Status.Unauthorized)
    assert(
      result.as[String].unsafeRunSync() ==
        "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Authentication is required.\"}"
    )
  }

  test("authenticated routes verify once and pass the middleware caller to the service") {
    var authentications = 0
    var observedCaller = Option.empty[Caller]
    val countingSecurity = new RouteSecurity(
      _ => IO { authentications += 1; Some(caller) },
      handler
    )
    val service = new IdentityAdapter {
      override def me(authenticated: Caller): IO[Response[IO]] = {
        observedCaller = Some(authenticated)
        response("identity")
      }
    }

    assertBody(
      countingSecurity(new IdentityRoutes(service, handler).routes),
      request(Method.GET, "/api/v1/me"),
      "identity"
    )
    assert(authentications == 1)
    assert(observedCaller.contains(caller))
  }

  test("feature modules do not claim routes from sibling domains") {
    val routes = security(new BookmarkRoutes(new BookmarkAdapter {}, handler).routes)

    assert(routes.run(request(Method.GET, "/api/v1/messages")).value.unsafeRunSync().isEmpty)
  }

  test("aggregate routes preserve public fallthrough, response metadata, unknown 404, and one protected auth") {
    var authentications = 0
    var observedCaller = Option.empty[Caller]
    val aggregateSecurity = new RouteSecurity(
      request =>
        IO {
          authentications += 1
          if (Wire.header(request, "Authorization").contains("Bearer valid")) Some(caller) else None
        },
      handler
    )
    val health = new HealthRoutes(
      new HealthOperations {
        override def healthz: IO[Response[IO]] = response("health")
        override def readyz: IO[Response[IO]] = response("ready")
      },
      handler
    )
    val identity = new IdentityRoutes(
      new IdentityAdapter {
        override def me(authenticated: Caller): IO[Response[IO]] = {
          observedCaller = Some(authenticated)
          response("identity")
        }
      },
      handler
    )
    val bookmarks = new BookmarkRoutes(
      new BookmarkAdapter {
        override def listBookmarksV1(req: Request[IO], attached: Option[Caller]): IO[Response[IO]] = {
          assert(attached.isEmpty)
          response("public-bookmarks")
        }
      },
      handler
    )
    val messages = new MessageRoutes(
      new MessageAdapter {
        override def messageBundle(req: Request[IO]): IO[Response[IO]] = IO.pure(
          Wire.jsonResponse(
            Status.Ok,
            Json.fromString("bundle"),
            "ETag" -> "\"bundle-fr\"",
            "Content-Language" -> Wire.first(Wire.query(req), "lang").getOrElse("en")
          )
        )
      },
      handler
    )
    val routes = StackverseRoutes.compose(
      health.routes,
      aggregateSecurity,
      identity.routes,
      bookmarks.routes,
      messages.routes,
      new ModerationRoutes(new ModerationAdapter {}, handler).routes,
      new AdminRoutes(new AdminAdapter {}, handler).routes
    )

    assertBody(routes, request(Method.GET, "/api/v1/bookmarks"), "public-bookmarks")
    val bundle = routes.orNotFound.run(request(Method.GET, "/api/v1/messages/bundle?lang=fr")).unsafeRunSync()
    assert(bundle.status == Status.Ok)
    assert(header(bundle, "ETag").contains("\"bundle-fr\""))
    assert(header(bundle, "Content-Language").contains("fr"))
    assert(routes.orNotFound.run(request(Method.GET, "/api/v1/unknown")).unsafeRunSync().status == Status.NotFound)

    authentications = 0
    val authenticated = request(Method.GET, "/api/v1/me")
      .putHeaders(Header.Raw(CIString("Authorization"), "Bearer valid"))
    assertBody(routes, authenticated, "identity")
    assert(authentications == 1)
    assert(observedCaller.contains(caller))
  }

  test("message validation accepts canonical dotted keys") {
    val input = InputValidation.validateMessageInput(
      JsonObject(
        "key" -> Json.fromString("bookmark.created"),
        "language" -> Json.fromString("en"),
        "text" -> Json.fromString("Created")
      )
    )

    assert(input.key == "bookmark.created")
    assert(input.language == "en")
  }

  test("server lifecycle logs surround actual server use and release") {
    val order = ListBuffer.empty[String]
    val server = Resource.make(IO { order += "acquire"; "server" })(_ => IO { order += "release"; () })
    val events = new ServerEvents {
      override def started: IO[Unit] = IO { order += "start"; () }
      override def stopped: IO[Unit] = IO { order += "stop"; () }
      override def startupFailed(error: Throwable): IO[Unit] = IO { order += "fatal"; () }
    }

    ServerLifecycle.attach(server, events).use(_ => IO { order += "use"; () }).unsafeRunSync()

    assert(order.toSeq == Seq("acquire", "start", "use", "release", "stop"))
  }

  test("server lifecycle releases partial startup, logs fatal, and rethrows the cause") {
    val order = ListBuffer.empty[String]
    val cause = new IllegalStateException("migration failed")
    val runtime = Resource
      .make(IO { order += "acquire"; () })(_ => IO { order += "release"; () })
      .flatMap(_ => Resource.eval(IO.raiseError[Unit](cause)))
    var observed = Option.empty[Throwable]
    val events = new ServerEvents {
      override def started: IO[Unit] = IO { order += "start"; () }
      override def stopped: IO[Unit] = IO { order += "stop"; () }
      override def startupFailed(error: Throwable): IO[Unit] = IO {
        observed = Some(error)
        order += "fatal"
        ()
      }
    }

    val thrown = intercept[IllegalStateException] {
      ServerLifecycle.attach(runtime, events).use(_ => IO.unit).unsafeRunSync()
    }

    assert(thrown eq cause)
    assert(observed.contains(cause))
    assert(order.toSeq == Seq("acquire", "release", "fatal"))
  }

  private def assertBody(routes: HttpRoutes[IO], request: Request[IO], expected: String): Unit = {
    val result = routes.orNotFound.run(request).unsafeRunSync()
    assert(result.status == Status.Ok)
    assert((Json.fromString(expected).noSpaces) == result.as[String].unsafeRunSync())
  }

  private def response(value: String): IO[Response[IO]] =
    IO.pure(Wire.jsonResponse(Status.Ok, Json.fromString(value)))

  private def request(method: Method, path: String): Request[IO] =
    Request[IO](method, Uri.unsafeFromString(path))

  private def header(response: Response[IO], name: String): Option[String] =
    response.headers.headers.find(_.name.toString.equalsIgnoreCase(name)).map(_.value)

  private def unsupported: IO[Response[IO]] = IO.raiseError(new AssertionError("unexpected service call"))

  private trait IdentityAdapter extends IdentityOperations {
    override def me(caller: Caller): IO[Response[IO]] = unsupported
  }

  private trait BookmarkAdapter extends BookmarkOperations {
    override def listBookmarksV1(req: Request[IO], caller: Option[Caller]): IO[Response[IO]] = unsupported
    override def listBookmarksV2(req: Request[IO], caller: Option[Caller]): IO[Response[IO]] = unsupported
    override def createBookmark(req: Request[IO], caller: Caller): IO[Response[IO]] = unsupported
    override def getBookmark(req: Request[IO], id: String, caller: Option[Caller]): IO[Response[IO]] = unsupported
    override def updateBookmark(req: Request[IO], id: String, caller: Caller): IO[Response[IO]] = unsupported
    override def deleteBookmark(req: Request[IO], id: String, caller: Caller): IO[Response[IO]] = unsupported
    override def listTags(req: Request[IO], caller: Caller): IO[Response[IO]] = unsupported
  }

  private trait MessageAdapter extends MessageOperations {
    override def listMessages(req: Request[IO]): IO[Response[IO]] = unsupported
    override def messageBundle(req: Request[IO]): IO[Response[IO]] = unsupported
    override def getMessage(req: Request[IO], id: String): IO[Response[IO]] = unsupported
    override def createMessage(req: Request[IO], caller: Caller): IO[Response[IO]] = unsupported
    override def updateMessage(req: Request[IO], id: String, caller: Caller): IO[Response[IO]] = unsupported
    override def deleteMessage(req: Request[IO], id: String, caller: Caller): IO[Response[IO]] = unsupported
  }

  private trait ModerationAdapter extends ModerationOperations {
    override def createReport(req: Request[IO], id: String, caller: Caller): IO[Response[IO]] = unsupported
    override def listMyReports(req: Request[IO], caller: Caller): IO[Response[IO]] = unsupported
    override def updateMyReport(req: Request[IO], id: String, caller: Caller): IO[Response[IO]] = unsupported
    override def withdrawReport(req: Request[IO], id: String, caller: Caller): IO[Response[IO]] = unsupported
    override def listReports(req: Request[IO], caller: Caller): IO[Response[IO]] = unsupported
    override def resolveReport(req: Request[IO], id: String, caller: Caller): IO[Response[IO]] = unsupported
    override def setBookmarkStatus(req: Request[IO], id: String, caller: Caller): IO[Response[IO]] = unsupported
  }

  private trait AdminAdapter extends AdminOperations {
    override def listUsers(req: Request[IO], caller: Caller): IO[Response[IO]] = unsupported
    override def getUser(req: Request[IO], username: String, caller: Caller): IO[Response[IO]] = unsupported
    override def setUserStatus(req: Request[IO], username: String, caller: Caller): IO[Response[IO]] = unsupported
    override def auditLog(req: Request[IO], caller: Caller): IO[Response[IO]] = unsupported
    override def stats(req: Request[IO], caller: Caller): IO[Response[IO]] = unsupported
  }
}
