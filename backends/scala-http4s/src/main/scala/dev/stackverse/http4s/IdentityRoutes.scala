package dev.stackverse.http4s

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

final class IdentityRoutes(auth: AuthService, handler: RequestHandler) {
  import Wire.*

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ GET -> Root / "api" / "v1" / "me" =>
    handler(req)(me(req))
  }

  private def me(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    jsonResponse(Status.Ok, auth.me(auth.requireCaller(req)))
  }
}
