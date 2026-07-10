package dev.stackverse.http4s

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

final class BookmarkRoutes(service: BookmarkOperations, handler: RequestHandler) {
  val routes: AuthedRoutes[Option[Caller], IO] = AuthedRoutes.of {
    case req @ GET -> Root / "api" / "v1" / "bookmarks" as caller =>
      handler(req.req)(service.listBookmarksV1(req.req, caller))
    case req @ GET -> Root / "api" / "v2" / "bookmarks" as caller =>
      handler(req.req)(service.listBookmarksV2(req.req, caller))
    case req @ GET -> Root / "api" / "v1" / "bookmarks" / id as caller =>
      handler(req.req)(service.getBookmark(req.req, id, caller))
    case req @ POST -> Root / "api" / "v1" / "bookmarks" as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.createBookmark(req.req, _)))
    case req @ PUT -> Root / "api" / "v1" / "bookmarks" / id as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.updateBookmark(req.req, id, _)))
    case req @ DELETE -> Root / "api" / "v1" / "bookmarks" / id as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.deleteBookmark(req.req, id, _)))
    case req @ GET -> Root / "api" / "v1" / "tags" as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.listTags(req.req, _)))
  }
}
