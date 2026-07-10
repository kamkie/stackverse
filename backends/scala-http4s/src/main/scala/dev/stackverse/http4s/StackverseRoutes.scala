package dev.stackverse.http4s

import cats.effect.IO
import cats.syntax.semigroupk.*
import org.http4s.HttpRoutes

final class StackverseRoutes(db: Db, auth: AuthService, i18n: I18n, logger: EventLogger) {
  private val handler = new ApiHandler(i18n, logger)
  private val repository = new SharedRepository(db)

  val routes: HttpRoutes[IO] =
    new HealthRoutes(db, logger, handler).routes <+>
      new IdentityRoutes(auth, handler).routes <+>
      new BookmarkRoutes(db, auth, repository, handler).routes <+>
      new MessageRoutes(db, auth, i18n, logger, repository, handler).routes <+>
      new ModerationRoutes(db, auth, logger, repository, handler).routes <+>
      new AdminRoutes(db, auth, logger, repository, handler).routes
}
