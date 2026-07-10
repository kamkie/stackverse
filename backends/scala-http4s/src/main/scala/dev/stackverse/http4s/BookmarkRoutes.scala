package dev.stackverse.http4s

import cats.effect.IO
import cats.syntax.semigroupk.*
import org.http4s.*
import org.http4s.dsl.io.*

final class BookmarkRoutes(service: BookmarkOperations, handler: RequestHandler, security: RouteAuthentication) {
  private val public: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "v1" / "bookmarks"      => handler(req)(service.listBookmarksV1(req))
    case req @ GET -> Root / "api" / "v2" / "bookmarks"      => handler(req)(service.listBookmarksV2(req))
    case req @ GET -> Root / "api" / "v1" / "bookmarks" / id => handler(req)(service.getBookmark(req, id))
  }

  private val secured: AuthedRoutes[Caller, IO] = AuthedRoutes.of {
    case req @ POST -> Root / "api" / "v1" / "bookmarks" as _     => handler(req.req)(service.createBookmark(req.req))
    case req @ PUT -> Root / "api" / "v1" / "bookmarks" / id as _ =>
      handler(req.req)(service.updateBookmark(req.req, id))
    case req @ DELETE -> Root / "api" / "v1" / "bookmarks" / id as _ =>
      handler(req.req)(service.deleteBookmark(req.req, id))
    case req @ GET -> Root / "api" / "v1" / "tags" as _ => handler(req.req)(service.listTags(req.req))
  }

  val routes: HttpRoutes[IO] = public <+> security(secured)
}
