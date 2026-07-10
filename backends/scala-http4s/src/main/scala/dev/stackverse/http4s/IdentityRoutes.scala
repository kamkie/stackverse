package dev.stackverse.http4s

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

final class IdentityRoutes(service: IdentityOperations, handler: RequestHandler, security: RouteAuthentication) {
  private val secured: AuthedRoutes[Caller, IO] = AuthedRoutes.of {
    case req @ GET -> Root / "api" / "v1" / "me" as caller =>
      handler(req.req)(service.me(caller))
  }

  val routes: HttpRoutes[IO] = security(secured)
}
