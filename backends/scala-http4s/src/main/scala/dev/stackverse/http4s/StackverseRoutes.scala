package dev.stackverse.http4s

import cats.effect.IO
import cats.syntax.semigroupk.*
import org.http4s.HttpRoutes

final class StackverseRoutes(db: Db, auth: AuthService, i18n: I18n, logger: EventLogger) {
  private val handler = new ApiHandler(i18n, logger)
  private val repository = new SharedRepository(db)
  private val security = RouteSecurity(auth, handler)
  private val health = new HealthService(db, logger)
  private val identity = new IdentityService(auth)
  private val bookmarks = new BookmarkService(db, auth, repository)
  private val messages = new MessageService(db, auth, i18n, logger, repository)
  private val moderation = new ModerationService(db, auth, logger, repository)
  private val admin = new AdminService(db, auth, logger, repository)

  val routes: HttpRoutes[IO] =
    new HealthRoutes(health, handler).routes <+>
      new IdentityRoutes(identity, handler, security).routes <+>
      new BookmarkRoutes(bookmarks, handler, security).routes <+>
      new MessageRoutes(messages, handler, security).routes <+>
      new ModerationRoutes(moderation, handler, security).routes <+>
      new AdminRoutes(admin, handler, security).routes
}
