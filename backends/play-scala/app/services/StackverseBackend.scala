package services

import config.BackendConfig
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import repositories.Db

import java.nio.file.Files
import javax.inject._
import scala.jdk.CollectionConverters._

@Singleton
class StackverseBackend @Inject() (
    lifecycle: ApplicationLifecycle,
    config: BackendConfig,
    logger: EventLogger,
    db: Db
) {
  try {
    db.migrate()
    seedMessages()
    logger.event(
      "info",
      "application_start",
      "success",
      s"Stackverse backend (play-scala) listening on :${config.port}",
      "port" -> JsNumber(config.port),
      "db_host" -> JsString(config.dbHost),
      "db_port" -> JsNumber(config.dbPort),
      "db_name" -> JsString(config.dbName),
      "oidc_issuer" -> JsString(config.oidcIssuerUri),
      "oidc_jwks_uri" -> JsString(config.oidcJwksUri.getOrElse("(via OIDC discovery)")),
      "seed_messages_dir" -> JsString(config.seedMessagesDir.toString),
      "log_level" -> JsString(config.logLevel),
      "log_format" -> JsString(config.logFormat),
      "otel_enabled" -> JsBoolean(config.otelEnabled)
    )
  } catch {
    case error: Throwable =>
      logger.line("fatal", s"Failed to start: ${error.getClass.getSimpleName}")
      throw error
  }

  lifecycle.addStopHook { () =>
    logger.event("info", "application_stop", "success", "Shutting down Play Scala backend")
    db.close()
    logger.shutdown()
    scala.concurrent.Future.successful(())
  }

  def seedMessages(): Unit = {
    if (!Files.isDirectory(config.seedMessagesDir)) {
      throw new IllegalStateException(s"Message seed directory not found: ${config.seedMessagesDir}")
    }
    val files = Files.list(config.seedMessagesDir)
    try {
      files.iterator().asScala.filter(path => path.getFileName.toString.endsWith(".json")).toSeq.sortBy(_.getFileName.toString).foreach { file =>
        val language = file.getFileName.toString.stripSuffix(".json")
        val entries = Json.parse(Files.readString(file)).as[JsObject].fields.map { case (key, value) => key -> value.as[String] }
        val inserted = db.withConnection { conn =>
          val sql =
            """insert into messages (id, key, language, text, created_at, updated_at)
              |select gen_random_uuid(), key, ?, text, now(), now()
              |from unnest(?::text[], ?::text[]) as seed(key, text)
              |on conflict (key, language) do nothing""".stripMargin
          db.execute(conn, sql, Seq(language, entries.map(_._1), entries.map(_._2)))
        }
        logger.event(
          "info",
          "message_seed_imported",
          "success",
          s"Message seed '$language': $inserted inserted, ${entries.size - inserted} already present",
          "language" -> JsString(language),
          "inserted" -> JsNumber(inserted),
          "skipped" -> JsNumber(entries.size - inserted)
        )
      }
    } finally {
      files.close()
    }
  }
}
