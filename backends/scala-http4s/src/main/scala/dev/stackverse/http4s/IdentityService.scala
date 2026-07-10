package dev.stackverse.http4s

import cats.effect.IO
import org.http4s.*

trait IdentityOperations {
  def me(req: Request[IO]): IO[Response[IO]]
}

final class IdentityService(auth: AuthService) extends IdentityOperations {
  import Wire.*

  override def me(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    jsonResponse(Status.Ok, auth.me(auth.requireCaller(req)))
  }
}
