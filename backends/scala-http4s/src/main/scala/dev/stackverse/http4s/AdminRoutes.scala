package dev.stackverse.http4s

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

final class AdminRoutes(service: AdminOperations, handler: RequestHandler, security: RouteAuthentication) {
  private val secured: AuthedRoutes[Caller, IO] = AuthedRoutes.of {
    case req @ GET -> Root / "api" / "v1" / "admin" / "users" as _ => handler(req.req)(service.listUsers(req.req))
    case req @ GET -> Root / "api" / "v1" / "admin" / "users" / username as _ =>
      handler(req.req)(service.getUser(req.req, username))
    case req @ PUT -> Root / "api" / "v1" / "admin" / "users" / username / "status" as _ =>
      handler(req.req)(service.setUserStatus(req.req, username))
    case req @ GET -> Root / "api" / "v1" / "admin" / "audit-log" as _ => handler(req.req)(service.auditLog(req.req))
    case req @ GET -> Root / "api" / "v1" / "admin" / "stats" as _     => handler(req.req)(service.stats(req.req))
  }

  val routes: HttpRoutes[IO] = security(secured)
}
