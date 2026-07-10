package dev.stackverse.http4s

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

final class IdentityRoutes(service: IdentityOperations, handler: RequestHandler) {
  val routes: AuthedRoutes[Option[Caller], IO] = AuthedRoutes.of {
    case req @ GET -> Root / "api" / "v1" / "me" as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.me))
  }
}
