package dev.stackverse.http4s

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

final class AdminRoutes(service: AdminOperations, handler: RequestHandler) {
  val routes: AuthedRoutes[Option[Caller], IO] = AuthedRoutes.of {
    case req @ GET -> Root / "api" / "v1" / "admin" / "users" as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.listUsers(req.req, _)))
    case req @ GET -> Root / "api" / "v1" / "admin" / "users" / username as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.getUser(req.req, username, _)))
    case req @ PUT -> Root / "api" / "v1" / "admin" / "users" / username / "status" as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.setUserStatus(req.req, username, _)))
    case req @ GET -> Root / "api" / "v1" / "admin" / "audit-log" as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.auditLog(req.req, _)))
    case req @ GET -> Root / "api" / "v1" / "admin" / "stats" as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.stats(req.req, _)))
  }
}
