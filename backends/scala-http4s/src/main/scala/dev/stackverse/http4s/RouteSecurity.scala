package dev.stackverse.http4s

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import org.http4s.server.AuthMiddleware
import org.http4s.{AuthedRoutes, HttpRoutes, Request, Response}

trait RouteAuthentication {
  def apply(routes: AuthedRoutes[Option[Caller], IO]): HttpRoutes[IO]
}

final class RouteSecurity(authenticateRequest: Request[IO] => IO[Option[Caller]], handler: RequestHandler)
    extends RouteAuthentication {
  private val authenticate: Kleisli[IO, Request[IO], Either[Response[IO], Option[Caller]]] =
    Kleisli { request =>
      authenticateRequest(request).attempt.flatMap {
        case Right(caller) => IO.pure(Right(caller))
        case Left(error)   => handler(request)(IO.raiseError(error)).map(Left(_))
      }
    }

  private val onFailure: AuthedRoutes[Response[IO], IO] =
    Kleisli(request => OptionT.pure[IO](request.context))

  private val authenticated: AuthMiddleware[IO, Option[Caller]] =
    AuthMiddleware(authenticate, onFailure)

  override def apply(routes: AuthedRoutes[Option[Caller], IO]): HttpRoutes[IO] = authenticated(routes)
}

object RouteSecurity {
  def apply(auth: AuthService, handler: RequestHandler): RouteSecurity =
    new RouteSecurity(request => IO.blocking(auth.optional(request)), handler)

  def requireCaller[A](caller: Option[Caller])(operation: Caller => IO[A]): IO[A] =
    caller.fold(IO.raiseError[A](UnauthorizedProblem()))(operation)
}
