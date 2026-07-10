package dev.stackverse.http4s

import cats.effect.IO
import io.circe.{Json, JsonObject}
import org.http4s.*
import org.http4s.dsl.io.*

import java.sql.{Connection, SQLException}
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

final class MessageRoutes(
    db: Db,
    auth: AuthService,
    i18n: I18n,
    logger: EventLogger,
    repository: SharedRepository,
    handler: RequestHandler
) {
  import InputValidation.*
  import RouteSupport.*
  import Wire.*
  import repository.*

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "v1" / "messages" / "bundle" => handler(req)(messageBundle(req))
    case req @ GET -> Root / "api" / "v1" / "messages"            => handler(req)(listMessages(req))
    case req @ POST -> Root / "api" / "v1" / "messages"           => handler(req)(createMessage(req))
    case req @ GET -> Root / "api" / "v1" / "messages" / id       => handler(req)(getMessage(req, id))
    case req @ PUT -> Root / "api" / "v1" / "messages" / id       => handler(req)(updateMessage(req, id))
    case req @ DELETE -> Root / "api" / "v1" / "messages" / id    => handler(req)(deleteMessage(req, id))
  }

  private def listMessages(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    auth.optional(req)
    val (page, size) = paging(query(req))
    val key = single(query(req), "key")
    val language = single(query(req), "language")
    val q = single(query(req), "q")
    maxLength(q, 200, "q")
    val clauses = ArrayBuffer("true")
    val params = ArrayBuffer.empty[Any]
    key.foreach { value => clauses += "key = ?"; params += value }
    language.foreach { value => clauses += "language = ?"; params += value }
    q.filter(_.trim.nonEmpty).foreach { value =>
      val pattern = s"%${escapeLike(value)}%"
      clauses += "(key ilike ? escape '\\' or text ilike ? escape '\\')"
      params += pattern
      params += pattern
    }
    val where = clauses.mkString(" and ")
    val payload = db.withConnection { conn =>
      val rows = db.query(
        conn,
        s"select * from messages where $where order by key, language limit ? offset ?",
        params.toSeq ++ Seq(size, page * size)
      )(Rows.message)
      val total = count(conn, s"select count(*) as count from messages where $where", params.toSeq)
      pagePayload(rows.map(Responses.message), page, size, total)
    }
    withEtag(req, payload)
  }

  private def messageBundle(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    auth.optional(req)
    val language = requestLanguage(req)
    withEtag(
      req,
      Json.obj("language" -> Json.fromString(language), "messages" -> Json.fromJsonObject(i18n.bundle(language))),
      "Content-Language" -> language
    )
  }

  private def getMessage(req: Request[IO], id: String): IO[Response[IO]] = IO.blocking {
    auth.optional(req)
    val uuid = parseUuid(id)
    val row = db.withConnection { conn =>
      db.one(conn, "select * from messages where id = ?", Seq(uuid))(Rows.message).getOrElse(throw NotFoundProblem())
    }
    withEtag(req, Responses.message(row))
  }

  private def createMessage(req: Request[IO]): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireRole(req, "admin")
        val input = validateMessageInput(body)
        val row = db.transaction { conn =>
          if (messageDuplicate(conn, input.key, input.language, None)) throw duplicateMessage(input)
          val inserted =
            try
              db.returning(
                conn,
                """insert into messages (id, key, language, text, description, created_at, updated_at)
                |values (?, ?, ?, ?, ?, ?, ?) returning *""".stripMargin,
                Seq(
                  UUID.randomUUID(),
                  input.key,
                  input.language,
                  input.text,
                  input.description,
                  Instant.now(),
                  Instant.now()
                )
              )(Rows.message)
            catch {
              case error: SQLException if SqlErrors.state(error).contains("23505") => throw duplicateMessage(input)
            }
          recordAudit(
            conn,
            caller.username,
            "message.created",
            "message",
            inserted.id.toString,
            messageSnapshot(inserted)
          )
          inserted
        }
        logMessage("message_created", "Message created", caller.username, row)
        jsonResponse(Status.Created, Responses.message(row), "Location" -> s"/api/v1/messages/${row.id}")
      }
    } yield response

  private def updateMessage(req: Request[IO], id: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireRole(req, "admin")
        val uuid = parseUuid(id)
        val input = validateMessageInput(body)
        val row = db.transaction { conn =>
          db.one(conn, "select 1 from messages where id = ?", Seq(uuid))(_ => true).getOrElse(throw NotFoundProblem())
          if (messageDuplicate(conn, input.key, input.language, Some(uuid))) throw duplicateMessage(input)
          val updated =
            try
              db.returning(
                conn,
                """update messages set key = ?, language = ?, text = ?, description = ?, updated_at = ?
                |where id = ? returning *""".stripMargin,
                Seq(input.key, input.language, input.text, input.description, Instant.now(), uuid)
              )(Rows.message)
            catch {
              case error: SQLException if SqlErrors.state(error).contains("23505") => throw duplicateMessage(input)
            }
          recordAudit(
            conn,
            caller.username,
            "message.updated",
            "message",
            updated.id.toString,
            messageSnapshot(updated)
          )
          updated
        }
        logMessage("message_updated", "Message updated", caller.username, row)
        jsonResponse(Status.Ok, Responses.message(row))
      }
    } yield response

  private def deleteMessage(req: Request[IO], id: String): IO[Response[IO]] = IO.blocking {
    val caller = auth.requireRole(req, "admin")
    val uuid = parseUuid(id)
    val row = db.transaction { conn =>
      val deleted = db
        .one(conn, "delete from messages where id = ? returning *", Seq(uuid))(Rows.message)
        .getOrElse(throw NotFoundProblem())
      recordAudit(conn, caller.username, "message.deleted", "message", deleted.id.toString, messageSnapshot(deleted))
      deleted
    }
    logMessage("message_deleted", "Message deleted", caller.username, row)
    Response[IO](Status.NoContent)
  }

  private def messageDuplicate(conn: Connection, key: String, language: String, except: Option[UUID]): Boolean =
    except match {
      case Some(id) =>
        db.one(conn, "select 1 from messages where key = ? and language = ? and id <> ?", Seq(key, language, id))(_ =>
          true
        ).getOrElse(false)
      case None =>
        db.one(conn, "select 1 from messages where key = ? and language = ?", Seq(key, language))(_ => true)
          .getOrElse(false)
    }

  private def duplicateMessage(input: MessageInput): ConflictProblem =
    ConflictProblem(s"A message with key '${input.key}' and language '${input.language}' already exists.")

  private def messageSnapshot(row: MessageRow): JsonObject =
    JsonObject.fromIterable(
      Seq(
        "key" -> Some(Json.fromString(row.key)),
        "language" -> Some(Json.fromString(row.language)),
        "text" -> Some(Json.fromString(row.text)),
        "description" -> row.description.map(Json.fromString)
      ).collect { case (key, Some(value)) => key -> value }
    )

  private def logMessage(event: String, description: String, actor: String, row: MessageRow): Unit =
    logger.event(
      "info",
      event,
      "success",
      description,
      "actor" -> Json.fromString(actor),
      "resource_type" -> Json.fromString("message"),
      "resource_id" -> Json.fromString(row.id.toString),
      "message_key" -> Json.fromString(row.key),
      "language" -> Json.fromString(row.language)
    )

  private def requestLanguage(request: Request[IO]): String =
    i18n.resolve(first(query(request), "lang"), header(request, "Accept-Language"))
}
