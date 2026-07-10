package dev.stackverse.http4s

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

final class ModerationRoutes(service: ModerationOperations, handler: RequestHandler, security: RouteAuthentication) {
  private val secured: AuthedRoutes[Caller, IO] = AuthedRoutes.of {
    case req @ POST -> Root / "api" / "v1" / "bookmarks" / id / "reports" as _ =>
      handler(req.req)(service.createReport(req.req, id))
    case req @ GET -> Root / "api" / "v1" / "reports" as _      => handler(req.req)(service.listMyReports(req.req))
    case req @ PUT -> Root / "api" / "v1" / "reports" / id as _ => handler(req.req)(service.updateMyReport(req.req, id))
    case req @ DELETE -> Root / "api" / "v1" / "reports" / id as _ =>
      handler(req.req)(service.withdrawReport(req.req, id))
    case req @ GET -> Root / "api" / "v1" / "admin" / "reports" as _ => handler(req.req)(service.listReports(req.req))
    case req @ PUT -> Root / "api" / "v1" / "admin" / "reports" / id as _ =>
      handler(req.req)(service.resolveReport(req.req, id))
    case req @ PUT -> Root / "api" / "v1" / "admin" / "bookmarks" / id / "status" as _ =>
      handler(req.req)(service.setBookmarkStatus(req.req, id))
  }

  val routes: HttpRoutes[IO] = security(secured)
}
