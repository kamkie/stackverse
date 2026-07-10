package dev.stackverse.http4s

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import org.http4s.implicits.*
import org.http4s.{HttpRoutes, Method, Request, Response, Status, Uri}
import org.scalatest.funsuite.AnyFunSuite

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
  private val security = new RouteSecurity(_ => IO.pure(caller), handler)

  test("health route executes its service behavior") {
    val service = new HealthOperations {
      override def healthz: IO[Response[IO]] = response("health")
      override def readyz: IO[Response[IO]] = response("ready")
    }

    assertBody(new HealthRoutes(service, handler).routes, request(Method.GET, "/healthz"), "health")
  }

  test("feature routes execute public and authenticated service behavior") {
    val identity = new IdentityAdapter {
      override def me(req: Request[IO]): IO[Response[IO]] = response("identity")
    }
    val bookmarks = new BookmarkAdapter {
      override def listBookmarksV1(req: Request[IO]): IO[Response[IO]] = response("bookmarks")
    }
    val messages = new MessageAdapter {
      override def createMessage(req: Request[IO]): IO[Response[IO]] = response("message-created")
    }
    val moderation = new ModerationAdapter {
      override def listMyReports(req: Request[IO]): IO[Response[IO]] = response("reports")
    }
    val admin = new AdminAdapter {
      override def listUsers(req: Request[IO]): IO[Response[IO]] = response("users")
    }

    val cases = Seq(
      (new IdentityRoutes(identity, handler, security).routes, request(Method.GET, "/api/v1/me"), "identity"),
      (new BookmarkRoutes(bookmarks, handler, security).routes, request(Method.GET, "/api/v1/bookmarks"), "bookmarks"),
      (
        new MessageRoutes(messages, handler, security).routes,
        request(Method.POST, "/api/v1/messages"),
        "message-created"
      ),
      (new ModerationRoutes(moderation, handler, security).routes, request(Method.GET, "/api/v1/reports"), "reports"),
      (new AdminRoutes(admin, handler, security).routes, request(Method.GET, "/api/v1/admin/users"), "users")
    )

    cases.foreach(assertBody.tupled)
  }

  test("Kleisli authentication middleware rejects secured routes before service execution") {
    val denied = new RouteSecurity(_ => IO.raiseError(UnauthorizedProblem()), handler)
    val routes = new IdentityRoutes(new IdentityAdapter {}, handler, denied).routes
    val result = routes.orNotFound.run(request(Method.GET, "/api/v1/me")).unsafeRunSync()

    assert(result.status == Status.Unauthorized)
    assert(
      result.as[String].unsafeRunSync() ==
        "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Authentication is required.\"}"
    )
  }

  test("feature modules do not claim routes from sibling domains") {
    val routes = new BookmarkRoutes(new BookmarkAdapter {}, handler, security).routes

    assert(routes.run(request(Method.GET, "/api/v1/messages")).value.unsafeRunSync().isEmpty)
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
    }

    ServerLifecycle.attach(server, events).use(_ => IO { order += "use"; () }).unsafeRunSync()

    assert(order.toSeq == Seq("acquire", "start", "use", "release", "stop"))
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

  private def unsupported: IO[Response[IO]] = IO.raiseError(new AssertionError("unexpected service call"))

  private trait IdentityAdapter extends IdentityOperations {
    override def me(req: Request[IO]): IO[Response[IO]] = unsupported
  }

  private trait BookmarkAdapter extends BookmarkOperations {
    override def listBookmarksV1(req: Request[IO]): IO[Response[IO]] = unsupported
    override def listBookmarksV2(req: Request[IO]): IO[Response[IO]] = unsupported
    override def createBookmark(req: Request[IO]): IO[Response[IO]] = unsupported
    override def getBookmark(req: Request[IO], id: String): IO[Response[IO]] = unsupported
    override def updateBookmark(req: Request[IO], id: String): IO[Response[IO]] = unsupported
    override def deleteBookmark(req: Request[IO], id: String): IO[Response[IO]] = unsupported
    override def listTags(req: Request[IO]): IO[Response[IO]] = unsupported
  }

  private trait MessageAdapter extends MessageOperations {
    override def listMessages(req: Request[IO]): IO[Response[IO]] = unsupported
    override def messageBundle(req: Request[IO]): IO[Response[IO]] = unsupported
    override def getMessage(req: Request[IO], id: String): IO[Response[IO]] = unsupported
    override def createMessage(req: Request[IO]): IO[Response[IO]] = unsupported
    override def updateMessage(req: Request[IO], id: String): IO[Response[IO]] = unsupported
    override def deleteMessage(req: Request[IO], id: String): IO[Response[IO]] = unsupported
  }

  private trait ModerationAdapter extends ModerationOperations {
    override def createReport(req: Request[IO], id: String): IO[Response[IO]] = unsupported
    override def listMyReports(req: Request[IO]): IO[Response[IO]] = unsupported
    override def updateMyReport(req: Request[IO], id: String): IO[Response[IO]] = unsupported
    override def withdrawReport(req: Request[IO], id: String): IO[Response[IO]] = unsupported
    override def listReports(req: Request[IO]): IO[Response[IO]] = unsupported
    override def resolveReport(req: Request[IO], id: String): IO[Response[IO]] = unsupported
    override def setBookmarkStatus(req: Request[IO], id: String): IO[Response[IO]] = unsupported
  }

  private trait AdminAdapter extends AdminOperations {
    override def listUsers(req: Request[IO]): IO[Response[IO]] = unsupported
    override def getUser(req: Request[IO], username: String): IO[Response[IO]] = unsupported
    override def setUserStatus(req: Request[IO], username: String): IO[Response[IO]] = unsupported
    override def auditLog(req: Request[IO]): IO[Response[IO]] = unsupported
    override def stats(req: Request[IO]): IO[Response[IO]] = unsupported
  }
}
