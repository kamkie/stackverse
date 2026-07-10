package dev.stackverse.http4s

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

final class ModerationRoutes(service: ModerationOperations, handler: RequestHandler, security: RouteAuthentication) {
  private val secured: AuthedRoutes[Caller, IO] = AuthedRoutes.of {
    case req @ POST -> Root / "api" / "v1" / "bookmarks" / id / "reports" as caller =>
      handler(req.req)(service.createReport(req.req, id, caller))
    case req @ GET -> Root / "api" / "v1" / "reports" as caller =>
      handler(req.req)(service.listMyReports(req.req, caller))
    case req @ PUT -> Root / "api" / "v1" / "reports" / id as caller =>
      handler(req.req)(service.updateMyReport(req.req, id, caller))
    case req @ DELETE -> Root / "api" / "v1" / "reports" / id as caller =>
      handler(req.req)(service.withdrawReport(req.req, id, caller))
    case req @ GET -> Root / "api" / "v1" / "admin" / "reports" as caller =>
      handler(req.req)(service.listReports(req.req, caller))
    case req @ PUT -> Root / "api" / "v1" / "admin" / "reports" / id as caller =>
      handler(req.req)(service.resolveReport(req.req, id, caller))
    case req @ PUT -> Root / "api" / "v1" / "admin" / "bookmarks" / id / "status" as caller =>
      handler(req.req)(service.setBookmarkStatus(req.req, id, caller))
  }

  val routes: HttpRoutes[IO] = security(secured)
}
