package dev.stackverse.http4s

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.dsl.io.*

final class HealthRoutes(db: Db, logger: EventLogger, handler: RequestHandler) {
  import Wire.*

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "healthz" =>
      handler(req)(IO.pure(jsonResponse(Status.Ok, Json.obj("status" -> Json.fromString("up")))))
    case req @ GET -> Root / "readyz" => handler(req)(readyz)
  }

  private def readyz: IO[Response[IO]] =
    IO.blocking {
      val started = System.nanoTime()
      try {
        db.withConnection(conn => db.one(conn, "select 1")(_.getInt(1)))
        jsonResponse(Status.Ok, Json.obj("status" -> Json.fromString("ready")))
      } catch {
        case error: Throwable =>
          logger.event(
            "warn",
            "dependency_call_failed",
            "failure",
            "Readiness lost: database unreachable",
            "dependency" -> Json.fromString("postgres"),
            "duration_ms" -> Json.fromLong((System.nanoTime() - started) / 1000000),
            "error_code" -> Json.fromString(SqlErrors.state(error).getOrElse("connection_error"))
          )
          jsonResponse(Status.ServiceUnavailable, Json.obj("status" -> Json.fromString("unavailable")))
      }
    }
}
