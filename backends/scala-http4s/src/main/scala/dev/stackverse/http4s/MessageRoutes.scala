package dev.stackverse.http4s

import cats.effect.IO
import cats.syntax.semigroupk.*
import org.http4s.*
import org.http4s.dsl.io.*

final class MessageRoutes(service: MessageOperations, handler: RequestHandler, security: RouteAuthentication) {
  private val public: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "v1" / "messages" / "bundle" => handler(req)(service.messageBundle(req))
    case req @ GET -> Root / "api" / "v1" / "messages"            => handler(req)(service.listMessages(req))
    case req @ GET -> Root / "api" / "v1" / "messages" / id       => handler(req)(service.getMessage(req, id))
  }

  private val secured: AuthedRoutes[Caller, IO] = AuthedRoutes.of {
    case req @ POST -> Root / "api" / "v1" / "messages" as caller =>
      handler(req.req)(service.createMessage(req.req, caller))
    case req @ PUT -> Root / "api" / "v1" / "messages" / id as caller =>
      handler(req.req)(service.updateMessage(req.req, id, caller))
    case req @ DELETE -> Root / "api" / "v1" / "messages" / id as caller =>
      handler(req.req)(service.deleteMessage(req.req, id, caller))
  }

  val routes: HttpRoutes[IO] = public <+> security(secured)
}
