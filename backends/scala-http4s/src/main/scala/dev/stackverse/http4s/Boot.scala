package dev.stackverse.http4s

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.parser.parse

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

object Boot {
  def seedMessages(config: BackendConfig, db: Db, logger: EventLogger): IO[Unit] =
    IO.blocking {
      if (!Files.isDirectory(config.seedMessagesDir)) {
        throw new IllegalStateException(s"Message seed directory not found: ${config.seedMessagesDir}")
      }
      val files = Files.list(config.seedMessagesDir)
      try
        files
          .iterator()
          .asScala
          .filter(_.getFileName.toString.endsWith(".json"))
          .toSeq
          .sortBy(_.getFileName.toString)
          .foreach { file =>
            val language = file.getFileName.toString.stripSuffix(".json")
            val entries = parse(Files.readString(file)).toOption
              .flatMap(_.asObject)
              .getOrElse(JsonObject.empty)
              .toIterable
              .toSeq
              .map { case (key, value) =>
                key -> value.asString.getOrElse("")
              }
            val inserted = db.withConnection { conn =>
              db.execute(
                conn,
                """insert into messages (id, key, language, text, created_at, updated_at)
                |select gen_random_uuid(), key, ?, text, now(), now()
                |from unnest(?::text[], ?::text[]) as seed(key, text)
                |on conflict (key, language) do nothing""".stripMargin,
                Seq(language, entries.map(_._1), entries.map(_._2))
              )
            }
            logger.event(
              "info",
              "message_seed_imported",
              "success",
              s"Message seed '$language': $inserted inserted, ${entries.size - inserted} already present",
              "language" -> Json.fromString(language),
              "inserted" -> Json.fromInt(inserted),
              "skipped" -> Json.fromInt(entries.size - inserted)
            )
          }
      finally
        files.close()
    }
}
