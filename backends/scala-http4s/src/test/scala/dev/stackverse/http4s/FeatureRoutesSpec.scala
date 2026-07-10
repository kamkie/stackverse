package dev.stackverse.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import org.http4s.implicits.*
import org.http4s.{Method, Request, Response, Status, Uri}
import org.scalatest.funsuite.AnyFunSuite

class FeatureRoutesSpec extends AnyFunSuite {
  private val passThrough = new RequestHandler {
    override def apply(req: Request[IO])(response: IO[Response[IO]]): IO[Response[IO]] = response
  }

  private val shortCircuit = new RequestHandler {
    override def apply(req: Request[IO])(response: IO[Response[IO]]): IO[Response[IO]] =
      IO.pure(Response[IO](Status.Accepted))
  }

  test("health routes serve liveness without infrastructure") {
    val response = new HealthRoutes(null, null, passThrough).routes.orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString("/healthz")))
      .unsafeRunSync()

    assert(response.status == Status.Ok)
    assert(response.as[String].unsafeRunSync() == "{\"status\":\"up\"}")
  }

  test("each feature module owns its representative route") {
    val cases = Seq(
      new IdentityRoutes(null, shortCircuit).routes -> Request[IO](Method.GET, Uri.unsafeFromString("/api/v1/me")),
      new BookmarkRoutes(null, null, null, shortCircuit).routes ->
        Request[IO](Method.GET, Uri.unsafeFromString("/api/v1/bookmarks")),
      new MessageRoutes(null, null, null, null, null, shortCircuit).routes ->
        Request[IO](Method.GET, Uri.unsafeFromString("/api/v1/messages")),
      new ModerationRoutes(null, null, null, null, shortCircuit).routes ->
        Request[IO](Method.GET, Uri.unsafeFromString("/api/v1/reports")),
      new AdminRoutes(null, null, null, null, shortCircuit).routes ->
        Request[IO](Method.GET, Uri.unsafeFromString("/api/v1/admin/users"))
    )

    cases.foreach { case (routes, request) =>
      assert(routes.orNotFound.run(request).unsafeRunSync().status == Status.Accepted)
    }
  }

  test("feature modules do not claim routes from sibling domains") {
    val request = Request[IO](Method.GET, Uri.unsafeFromString("/api/v1/messages"))

    assert(new BookmarkRoutes(null, null, null, shortCircuit).routes.run(request).value.unsafeRunSync().isEmpty)
  }

  test("central request handler renders exact API problem responses") {
    val request = Request[IO](Method.GET, Uri.unsafeFromString("/test-error"))
    val response = new ApiHandler(null, null)
      .apply(request)(IO.raiseError(BadRequestProblem("synthetic failure")))
      .unsafeRunSync()

    assert(response.status == Status.BadRequest)
    assert(
      response.as[String].unsafeRunSync() ==
        "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,\"detail\":\"synthetic failure\"}"
    )
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
}
