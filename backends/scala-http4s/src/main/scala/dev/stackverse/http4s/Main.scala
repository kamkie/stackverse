package dev.stackverse.http4s

import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.comcast.ip4s.{Host, Port}
import io.circe.Json
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    app.use(_ => IO.never).as(ExitCode.Success)

  private def app: Resource[IO, Unit] =
    for {
      config <- Resource.eval(IO.blocking(BackendConfig.load()))
      logger <- Resource.make(IO(new EventLogger(config)))(logger => IO(logger.shutdown()))
      db <- Resource.make(IO.blocking(new Db(config, logger)))(db => IO.blocking(db.close()))
      _ <- Resource.make(IO.unit)(_ =>
        IO(logger.event("info", "application_stop", "success", "Shutting down Scala http4s backend"))
      )
      _ <- Resource.eval(IO.blocking(db.migrate()))
      _ <- Resource.eval(Boot.seedMessages(config, db, logger))
      i18n = new I18n(db)
      auth = new AuthService(config, db, i18n, logger)
      routes = new StackverseRoutes(db, auth, i18n, logger)
      _ <- Resource.eval(
        IO(
          logger.event(
            "info",
            "application_start",
            "success",
            s"Stackverse backend (scala-http4s) listening on :${config.port}",
            "port" -> Json.fromInt(config.port),
            "db_host" -> Json.fromString(config.dbHost),
            "db_port" -> Json.fromInt(config.dbPort),
            "db_name" -> Json.fromString(config.dbName),
            "oidc_issuer" -> Json.fromString(config.oidcIssuerUri),
            "oidc_jwks_uri" -> Json.fromString(config.oidcJwksUri.getOrElse("(via OIDC discovery)")),
            "seed_messages_dir" -> Json.fromString(config.seedMessagesDir.toString),
            "log_level" -> Json.fromString(config.logLevel),
            "log_format" -> Json.fromString(config.logFormat),
            "otel_enabled" -> Json.fromBoolean(config.otelEnabled)
          )
        )
      )
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString("0.0.0.0").get)
        .withPort(Port.fromInt(config.port).getOrElse(Port.fromInt(8080).get))
        .withHttpApp(routes.routes.orNotFound)
        .build
    } yield ()
}
