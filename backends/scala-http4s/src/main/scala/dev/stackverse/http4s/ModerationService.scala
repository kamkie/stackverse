package dev.stackverse.http4s

import cats.effect.IO
import io.circe.{Json, JsonObject}
import org.http4s.*

import java.sql.{Connection, SQLException}
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

trait ModerationOperations {
  def createReport(req: Request[IO], id: String): IO[Response[IO]]
  def listMyReports(req: Request[IO]): IO[Response[IO]]
  def updateMyReport(req: Request[IO], id: String): IO[Response[IO]]
  def withdrawReport(req: Request[IO], id: String): IO[Response[IO]]
  def listReports(req: Request[IO]): IO[Response[IO]]
  def resolveReport(req: Request[IO], id: String): IO[Response[IO]]
  def setBookmarkStatus(req: Request[IO], id: String): IO[Response[IO]]
}

final class ModerationService(db: Db, auth: AuthService, logger: EventLogger, repository: SharedRepository)
    extends ModerationOperations {
  import InputValidation.*
  import RouteSupport.*
  import Wire.*
  import repository.*

  override def createReport(req: Request[IO], id: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireCaller(req)
        val bookmarkId = parseUuid(id)
        val input = validateReportInput(body)
        val row = db.transaction { conn =>
          val visible = db
            .one(conn, "select visibility, status from bookmarks where id = ? for update", Seq(bookmarkId)) { rs =>
              rs.getString("visibility") == "public" && rs.getString("status") == "active"
            }
            .getOrElse(false)
          if (!visible) throw NotFoundProblem()
          val open = db
            .one(
              conn,
              "select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open'",
              Seq(bookmarkId, caller.username)
            )(_ => true)
            .getOrElse(false)
          if (open) throw ConflictProblem("You already have an open report on this bookmark.")
          try
            db.returning(
              conn,
              """insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
                |values (?, ?, ?, ?, ?, 'open', ?) returning *""".stripMargin,
              Seq(UUID.randomUUID(), bookmarkId, caller.username, input.reason, input.comment, Instant.now())
            )(Rows.report)
          catch {
            case error: SQLException if SqlErrors.state(error).contains("23505") =>
              throw ConflictProblem("You already have an open report on this bookmark.")
          }
        }
        logger.event(
          "info",
          "report_created",
          "success",
          "Report created on a public bookmark",
          "actor" -> Json.fromString(caller.username),
          "resource_type" -> Json.fromString("report"),
          "resource_id" -> Json.fromString(row.id.toString),
          "bookmark_id" -> Json.fromString(bookmarkId.toString),
          "reason" -> Json.fromString(row.reason)
        )
        jsonResponse(Status.Created, Responses.report(row))
      }
    } yield response

  override def listMyReports(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    val caller = auth.requireCaller(req)
    val (page, size) = paging(query(req))
    val status = validatedReportStatus(single(query(req), "status"))
    val (where, params) = status match {
      case Some(value) => ("reporter = ? and status = ?", Seq(caller.username, value))
      case None        => ("reporter = ?", Seq(caller.username))
    }
    val payload = db.withConnection { conn =>
      val rows = db.query(
        conn,
        s"select * from reports where $where order by created_at desc, id desc limit ? offset ?",
        params ++ Seq(size, page * size)
      )(Rows.report)
      val total = count(conn, s"select count(*) as count from reports where $where", params)
      pagePayload(rows.map(Responses.report), page, size, total)
    }
    jsonResponse(Status.Ok, payload)
  }

  override def updateMyReport(req: Request[IO], id: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireCaller(req)
        val uuid = parseUuid(id)
        val input = validateReportInput(body)
        val row = db.transaction { conn =>
          val report = ownReport(conn, caller.username, uuid)
          requireOpen(report)
          db.returning(
            conn,
            "update reports set reason = ?, comment = ? where id = ? returning *",
            Seq(input.reason, input.comment, uuid)
          )(Rows.report)
        }
        logger.event(
          "info",
          "report_updated",
          "success",
          "Report updated by its reporter",
          "actor" -> Json.fromString(caller.username),
          "resource_type" -> Json.fromString("report"),
          "resource_id" -> Json.fromString(uuid.toString),
          "bookmark_id" -> Json.fromString(row.bookmarkId.toString),
          "reason" -> Json.fromString(row.reason)
        )
        jsonResponse(Status.Ok, Responses.report(row))
      }
    } yield response

  override def withdrawReport(req: Request[IO], id: String): IO[Response[IO]] = IO.blocking {
    val caller = auth.requireCaller(req)
    val uuid = parseUuid(id)
    val bookmarkId = db.transaction { conn =>
      val report = ownReport(conn, caller.username, uuid)
      requireOpen(report)
      db.execute(conn, "delete from reports where id = ?", Seq(uuid))
      report.bookmarkId
    }
    logger.event(
      "info",
      "report_withdrawn",
      "success",
      "Report withdrawn by its reporter",
      "actor" -> Json.fromString(caller.username),
      "resource_type" -> Json.fromString("report"),
      "resource_id" -> Json.fromString(uuid.toString),
      "bookmark_id" -> Json.fromString(bookmarkId.toString)
    )
    Response[IO](Status.NoContent)
  }

  override def listReports(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    auth.requireRole(req, "moderator")
    val (page, size) = paging(query(req))
    val status = validatedReportStatus(single(query(req), "status")).getOrElse("open")
    val payload = db.withConnection { conn =>
      val rows = db.query(
        conn,
        "select * from reports where status = ? order by created_at asc, id asc limit ? offset ?",
        Seq(status, size, page * size)
      )(Rows.report)
      val total = count(conn, "select count(*) as count from reports where status = ?", Seq(status))
      pagePayload(rows.map(Responses.report), page, size, total)
    }
    jsonResponse(Status.Ok, payload)
  }

  override def resolveReport(req: Request[IO], id: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireRole(req, "moderator")
        val uuid = parseUuid(id)
        val validator = Validator()
        val target = body("resolution").flatMap(_.asString)
        validator.check(
          target.exists(Seq("open", "dismissed", "actioned").contains),
          "resolution",
          "validation.resolution.invalid"
        )
        val note = body("note").flatMap(_.asString)
        validator.check(note.forall(_.length <= 1000), "note", "validation.resolution.note.too-long")
        validator.throwIfInvalid()
        val resolution = target.get
        val row = db.transaction { conn =>
          if (resolution == "actioned") {
            val bookmarkId = db
              .one(conn, "select bookmark_id from reports where id = ?", Seq(uuid))(
                _.getObject("bookmark_id", classOf[UUID])
              )
              .getOrElse(throw NotFoundProblem())
            db.one(conn, "select id from bookmarks where id = ? for update", Seq(bookmarkId))(_ => true)
          }
          val report = db
            .one(conn, "select * from reports where id = ? for update", Seq(uuid))(Rows.report)
            .getOrElse(throw NotFoundProblem())
          if (resolution == "open") {
            val conflict = db
              .one(
                conn,
                "select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open' and id <> ?",
                Seq(report.bookmarkId, report.reporter, uuid)
              )(_ => true)
              .getOrElse(false)
            if (conflict) throw ConflictProblem("The reporter already has another open report on this bookmark.")
            val reopened =
              try
                db.returning(
                  conn,
                  "update reports set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null where id = ? returning *",
                  Seq(uuid)
                )(Rows.report)
              catch {
                case error: SQLException if SqlErrors.state(error).contains("23505") =>
                  throw ConflictProblem("The reporter already has another open report on this bookmark.")
              }
            recordAudit(
              conn,
              caller.username,
              "report.reopened",
              "report",
              uuid.toString,
              JsonObject("bookmarkId" -> Json.fromString(report.bookmarkId.toString))
            )
            logger.event(
              "info",
              "report_reopened",
              "success",
              "Report re-opened",
              "actor" -> Json.fromString(caller.username),
              "resource_type" -> Json.fromString("report"),
              "resource_id" -> Json.fromString(uuid.toString),
              "bookmark_id" -> Json.fromString(report.bookmarkId.toString)
            )
            reopened
          } else {
            val resolved = resolveOne(conn, report, resolution, caller.username, note, autoResolved = false)
            if (resolution == "actioned") {
              hideBookmark(conn, caller.username, report.bookmarkId, note)
              val siblings = db.query(
                conn,
                "select * from reports where bookmark_id = ? and status = 'open' and id <> ? order by id asc for update",
                Seq(report.bookmarkId, uuid)
              )(Rows.report)
              siblings.foreach(resolveOne(conn, _, "actioned", caller.username, note, autoResolved = true))
            }
            resolved
          }
        }
        jsonResponse(Status.Ok, Responses.report(row))
      }
    } yield response

  override def setBookmarkStatus(req: Request[IO], id: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireRole(req, "moderator")
        val uuid = parseUuid(id)
        val validator = Validator()
        val status = body("status").flatMap(_.asString)
        validator.check(
          status.exists(value => value == "active" || value == "hidden"),
          "status",
          "validation.bookmark-status.invalid"
        )
        val note = body("note").flatMap(_.asString)
        validator.check(note.forall(_.length <= 1000), "note", "validation.bookmark-status.note.too-long")
        validator.throwIfInvalid()
        val row = db.transaction { conn =>
          val existing = db
            .one(conn, "select * from bookmarks where id = ? for update", Seq(uuid))(Rows.bookmark)
            .getOrElse(throw NotFoundProblem())
          val updated = db.returning(
            conn,
            "update bookmarks set status = ?, updated_at = ? where id = ? returning *",
            Seq(status.get, Instant.now(), uuid)
          )(Rows.bookmark)
          recordAudit(
            conn,
            caller.username,
            "bookmark.status-changed",
            "bookmark",
            uuid.toString,
            JsonObject(
              "from" -> Json.fromString(existing.status),
              "to" -> Json.fromString(status.get),
              "note" -> note.fold(Json.Null)(Json.fromString)
            )
          )
          logger.event(
            "info",
            "bookmark_status_changed",
            "success",
            "Bookmark moderation status changed",
            "actor" -> Json.fromString(caller.username),
            "resource_type" -> Json.fromString("bookmark"),
            "resource_id" -> Json.fromString(uuid.toString),
            "from" -> Json.fromString(existing.status),
            "to" -> Json.fromString(status.get)
          )
          updated
        }
        jsonResponse(Status.Ok, Responses.bookmark(row))
      }
    } yield response

  private def ownReport(conn: Connection, reporter: String, id: UUID): ReportRow = {
    val row = db
      .one(conn, "select * from reports where id = ? for update", Seq(id))(Rows.report)
      .getOrElse(throw NotFoundProblem())
    if (row.reporter != reporter) throw NotFoundProblem()
    row
  }

  private def requireOpen(report: ReportRow): Unit =
    if (report.status != "open") throw ConflictProblem("The report has already been resolved.")

  private def validatedReportStatus(value: Option[String]): Option[String] =
    value.map { status =>
      if (!Seq("open", "dismissed", "actioned").contains(status)) throw BadRequestProblem(s"unknown status: $status")
      status
    }

  private def resolveOne(
      conn: Connection,
      report: ReportRow,
      resolution: String,
      actor: String,
      note: Option[String],
      autoResolved: Boolean
  ): ReportRow = {
    val updated = db.returning(
      conn,
      "update reports set status = ?, resolved_by = ?, resolved_at = ?, resolution_note = ? where id = ? returning *",
      Seq(resolution, actor, Instant.now(), note, report.id)
    )(Rows.report)
    recordAudit(
      conn,
      actor,
      "report.resolved",
      "report",
      report.id.toString,
      JsonObject(
        "bookmarkId" -> Json.fromString(report.bookmarkId.toString),
        "resolution" -> Json.fromString(resolution),
        "note" -> note.fold(Json.Null)(Json.fromString),
        "autoResolved" -> Json.fromBoolean(autoResolved)
      )
    )
    logger.event(
      "info",
      "report_resolved",
      "success",
      "Report resolved",
      "actor" -> Json.fromString(actor),
      "resource_type" -> Json.fromString("report"),
      "resource_id" -> Json.fromString(report.id.toString),
      "bookmark_id" -> Json.fromString(report.bookmarkId.toString),
      "resolution" -> Json.fromString(resolution),
      "auto_resolved" -> Json.fromBoolean(autoResolved)
    )
    updated
  }

  private def hideBookmark(conn: Connection, actor: String, bookmarkId: UUID, note: Option[String]): Unit = {
    val bookmark = findBookmark(conn, bookmarkId).getOrElse(throw NotFoundProblem())
    if (bookmark.status == "hidden") return
    db.execute(
      conn,
      "update bookmarks set status = 'hidden', updated_at = ? where id = ?",
      Seq(Instant.now(), bookmarkId)
    )
    recordAudit(
      conn,
      actor,
      "bookmark.status-changed",
      "bookmark",
      bookmarkId.toString,
      JsonObject(
        "from" -> Json.fromString("active"),
        "to" -> Json.fromString("hidden"),
        "note" -> note.fold(Json.Null)(Json.fromString)
      )
    )
    logger.event(
      "info",
      "bookmark_status_changed",
      "success",
      "Bookmark hidden by an actioned report",
      "actor" -> Json.fromString(actor),
      "resource_type" -> Json.fromString("bookmark"),
      "resource_id" -> Json.fromString(bookmarkId.toString),
      "from" -> Json.fromString("active"),
      "to" -> Json.fromString("hidden")
    )
  }

}
