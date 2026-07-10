package dev.stackverse.http4s

import cats.effect.IO
import cats.syntax.semigroupk.*
import org.http4s.{AuthedRoutes, HttpRoutes}

final class StackverseRoutes(db: Db, auth: AuthService, i18n: I18n, logger: EventLogger) {
  private val handler = new ApiHandler(i18n, logger)
  private val repository = new SharedRepository(db)
  private val security = RouteSecurity(auth, handler)
  private val health = new HealthService(db, logger)
  private val identity = new IdentityService(auth)
  private val bookmarks = new BookmarkService(db, repository)
  private val messages = new MessageService(db, auth, i18n, logger, repository)
  private val moderation = new ModerationService(db, auth, logger, repository)
  private val admin = new AdminService(db, auth, logger, repository)

  val routes: HttpRoutes[IO] =
    StackverseRoutes.compose(
      new HealthRoutes(health, handler).routes,
      security,
      new IdentityRoutes(identity, handler).routes,
      new BookmarkRoutes(bookmarks, handler).routes,
      new MessageRoutes(messages, handler).routes,
      new ModerationRoutes(moderation, handler).routes,
      new AdminRoutes(admin, handler).routes
    )
}

object StackverseRoutes {
  def compose(
      health: HttpRoutes[IO],
      security: RouteAuthentication,
      api: AuthedRoutes[Option[Caller], IO]*
  ): HttpRoutes[IO] =
    health <+> security(api.reduce(_ <+> _))
}
