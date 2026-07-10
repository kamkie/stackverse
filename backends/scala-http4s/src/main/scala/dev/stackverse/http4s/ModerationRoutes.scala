package dev.stackverse.http4s

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

final class ModerationRoutes(service: ModerationOperations, handler: RequestHandler) {
  val routes: AuthedRoutes[Option[Caller], IO] = AuthedRoutes.of {
    case req @ POST -> Root / "api" / "v1" / "bookmarks" / id / "reports" as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.createReport(req.req, id, _)))
    case req @ GET -> Root / "api" / "v1" / "reports" as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.listMyReports(req.req, _)))
    case req @ PUT -> Root / "api" / "v1" / "reports" / id as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.updateMyReport(req.req, id, _)))
    case req @ DELETE -> Root / "api" / "v1" / "reports" / id as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.withdrawReport(req.req, id, _)))
    case req @ GET -> Root / "api" / "v1" / "admin" / "reports" as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.listReports(req.req, _)))
    case req @ PUT -> Root / "api" / "v1" / "admin" / "reports" / id as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.resolveReport(req.req, id, _)))
    case req @ PUT -> Root / "api" / "v1" / "admin" / "bookmarks" / id / "status" as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.setBookmarkStatus(req.req, id, _)))
  }
}
