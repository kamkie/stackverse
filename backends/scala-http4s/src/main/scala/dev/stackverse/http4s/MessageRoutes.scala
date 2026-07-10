package dev.stackverse.http4s

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

final class MessageRoutes(service: MessageOperations, handler: RequestHandler) {
  val routes: AuthedRoutes[Option[Caller], IO] = AuthedRoutes.of {
    case req @ GET -> Root / "api" / "v1" / "messages" / "bundle" as _ =>
      handler(req.req)(service.messageBundle(req.req))
    case req @ GET -> Root / "api" / "v1" / "messages" as _       => handler(req.req)(service.listMessages(req.req))
    case req @ GET -> Root / "api" / "v1" / "messages" / id as _  => handler(req.req)(service.getMessage(req.req, id))
    case req @ POST -> Root / "api" / "v1" / "messages" as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.createMessage(req.req, _)))
    case req @ PUT -> Root / "api" / "v1" / "messages" / id as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.updateMessage(req.req, id, _)))
    case req @ DELETE -> Root / "api" / "v1" / "messages" / id as caller =>
      handler(req.req)(RouteSecurity.requireCaller(caller)(service.deleteMessage(req.req, id, _)))
  }
}
