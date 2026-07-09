package controllers

import models.*
import play.api.libs.json.{JsObject, JsString, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, RequestHeader}
import repositories.{Db, Rows}
import services.{ApiAction, AuditService, AuthService, EventLogger, I18n, SqlErrors}
import support.{InputJson, MessageInput, Responses, Wire}
import support.InputJson.given
import support.Responses.given

import java.sql.{Connection, SQLException}
import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Singleton}

@Singleton
class MessageController @Inject() (
    cc: ControllerComponents,
    api: ApiAction,
    db: Db,
    auth: AuthService,
    i18n: I18n,
    logger: EventLogger,
    audit: AuditService
) extends AbstractController(cc) {
  import Wire.*

  def list: Action[AnyContent] = api { implicit request =>
    auth.optional(request)
    val (page, size) = paging(request.queryString)
    val key = single(request.queryString, "key")
    val language = single(request.queryString, "language")
    val q = single(request.queryString, "q")
    maxLength(q, 200, "q")
    val (where, params) = {
      val clauses = scala.collection.mutable.ArrayBuffer("true")
      val values = scala.collection.mutable.ArrayBuffer.empty[Any]
      key.foreach { value => clauses += "key = ?"; values += value }
      language.foreach { value => clauses += "language = ?"; values += value }
      q.filter(_.trim.nonEmpty).foreach { value =>
        val pattern = s"%${escapeLike(value)}%"
        clauses += "(key ilike ? escape '\\' or text ilike ? escape '\\')"
        values += pattern
        values += pattern
      }
      (clauses.mkString(" and "), values.toSeq)
    }
    val payload = db.withConnection { conn =>
      val rows = db.query(
        conn,
        s"select * from messages where $where order by key, language limit ? offset ?",
        params ++ Seq(size, page * size)
      )(Rows.message)
      val total = count(conn, s"select count(*) as count from messages where $where", params)
      Wire.page(rows.map(row => Json.toJson(row)), page, size, total)
    }
    withEtag(request, payload)
  }

  def bundle: Action[AnyContent] = api { implicit request =>
    auth.optional(request)
    val language = requestLanguage(request)
    withEtag(
      request,
      Json.obj("language" -> language, "messages" -> i18n.bundle(language)),
      "Content-Language" -> language
    )
  }

  def get(id: String): Action[AnyContent] = api { implicit request =>
    auth.optional(request)
    val uuid = parseUuid(id)
    val row = db.withConnection { conn =>
      db.one(conn, "select * from messages where id = ?", Seq(uuid))(Rows.message).getOrElse(throw new NotFoundProblem)
    }
    withEtag(request, Json.toJson(row))
  }

  def create: Action[AnyContent] = api { implicit request =>
    val caller = auth.requireRole(request, "admin")
    val input = InputJson.read[MessageInput](request)
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
      audit.record(conn, caller.username, "message.created", "message", inserted.id.toString, messageSnapshot(inserted))
      inserted
    }
    logMessage("message_created", "Message created", caller.username, row)
    Created(Json.toJson(row)).withHeaders("Location" -> s"/api/v1/messages/${row.id}")
  }

  def update(id: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.requireRole(request, "admin")
    val uuid = parseUuid(id)
    val input = InputJson.read[MessageInput](request)
    val row = db.transaction { conn =>
      db.one(conn, "select 1 from messages where id = ?", Seq(uuid))(_ => true).getOrElse(throw new NotFoundProblem)
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
      audit.record(conn, caller.username, "message.updated", "message", updated.id.toString, messageSnapshot(updated))
      updated
    }
    logMessage("message_updated", "Message updated", caller.username, row)
    Ok(Json.toJson(row))
  }

  def delete(id: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.requireRole(request, "admin")
    val uuid = parseUuid(id)
    val row = db.transaction { conn =>
      val deleted = db
        .one(conn, "delete from messages where id = ? returning *", Seq(uuid))(Rows.message)
        .getOrElse(throw new NotFoundProblem)
      audit.record(conn, caller.username, "message.deleted", "message", deleted.id.toString, messageSnapshot(deleted))
      deleted
    }
    logMessage("message_deleted", "Message deleted", caller.username, row)
    NoContent
  }

  private def requestLanguage(request: RequestHeader): String =
    i18n.resolve(first(request.queryString, "lang"), request.headers.get("Accept-Language"))

  private def count(conn: Connection, sql: String, params: Seq[Any] = Seq.empty): Long =
    db.one(conn, sql, params)(_.getLong("count")).getOrElse(0L)

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
    new ConflictProblem(s"A message with key '${input.key}' and language '${input.language}' already exists.")

  private def messageSnapshot(row: MessageRow): JsObject =
    Wire.obj(
      "key" -> Some(JsString(row.key)),
      "language" -> Some(JsString(row.language)),
      "text" -> Some(JsString(row.text)),
      "description" -> row.description.map(JsString.apply)
    )

  private def logMessage(event: String, description: String, actor: String, row: MessageRow): Unit =
    logger.event(
      "info",
      event,
      "success",
      description,
      "actor" -> JsString(actor),
      "resource_type" -> JsString("message"),
      "resource_id" -> JsString(row.id.toString),
      "message_key" -> JsString(row.key),
      "language" -> JsString(row.language)
    )
}
