package controllers

import models.*
import play.api.libs.json.{JsBoolean, JsString, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import repositories.{Db, Rows}
import services.{ApiAction, AuditService, AuthService, EventLogger, SqlErrors}
import support.{BookmarkStatusInput, InputJson, ReportInput, ResolutionInput, Responses, Wire}
import support.InputJson.given
import support.Responses.given

import java.sql.{Connection, SQLException}
import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Singleton}

@Singleton
class ModerationController @Inject() (
    cc: ControllerComponents,
    api: ApiAction,
    db: Db,
    auth: AuthService,
    logger: EventLogger,
    audit: AuditService
) extends AbstractController(cc) {
  import Wire.*

  def createReport(bookmarkId: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.requireCaller(request)
    val uuid = parseUuid(bookmarkId)
    val input = InputJson.read[ReportInput](request)
    val row = db.transaction { conn =>
      val visible = db
        .one(conn, "select visibility, status from bookmarks where id = ? for update", Seq(uuid)) { rs =>
          rs.getString("visibility") == "public" && rs.getString("status") == "active"
        }
        .getOrElse(false)
      if (!visible) throw new NotFoundProblem
      val open = db
        .one(
          conn,
          "select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open'",
          Seq(uuid, caller.username)
        )(_ => true)
        .getOrElse(false)
      if (open) throw new ConflictProblem("You already have an open report on this bookmark.")
      try
        db.returning(
          conn,
          """insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
            |values (?, ?, ?, ?, ?, 'open', ?) returning *""".stripMargin,
          Seq(UUID.randomUUID(), uuid, caller.username, input.reason, input.comment, Instant.now())
        )(Rows.report)
      catch {
        case error: SQLException if SqlErrors.state(error).contains("23505") =>
          throw new ConflictProblem("You already have an open report on this bookmark.")
      }
    }
    logger.event(
      "info",
      "report_created",
      "success",
      "Report created on a public bookmark",
      "actor" -> JsString(caller.username),
      "resource_type" -> JsString("report"),
      "resource_id" -> JsString(row.id.toString),
      "bookmark_id" -> JsString(uuid.toString),
      "reason" -> JsString(row.reason)
    )
    Created(Json.toJson(row))
  }

  def listOwnReports: Action[AnyContent] = api { implicit request =>
    val caller = auth.requireCaller(request)
    val (page, size) = paging(request.queryString)
    val status = validatedReportStatus(single(request.queryString, "status"))
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
      Wire.page(rows.map(row => Json.toJson(row)), page, size, total)
    }
    Ok(payload)
  }

  def updateOwnReport(id: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.requireCaller(request)
    val uuid = parseUuid(id)
    val input = InputJson.read[ReportInput](request)
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
      "actor" -> JsString(caller.username),
      "resource_type" -> JsString("report"),
      "resource_id" -> JsString(uuid.toString),
      "bookmark_id" -> JsString(row.bookmarkId.toString),
      "reason" -> JsString(row.reason)
    )
    Ok(Json.toJson(row))
  }

  def withdrawOwnReport(id: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.requireCaller(request)
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
      "actor" -> JsString(caller.username),
      "resource_type" -> JsString("report"),
      "resource_id" -> JsString(uuid.toString),
      "bookmark_id" -> JsString(bookmarkId.toString)
    )
    NoContent
  }

  def listReports: Action[AnyContent] = api { implicit request =>
    auth.requireRole(request, "moderator")
    val (page, size) = paging(request.queryString)
    val status = validatedReportStatus(single(request.queryString, "status")).getOrElse("open")
    val payload = db.withConnection { conn =>
      val rows = db.query(
        conn,
        "select * from reports where status = ? order by created_at asc, id asc limit ? offset ?",
        Seq(status, size, page * size)
      )(Rows.report)
      val total = count(conn, "select count(*) as count from reports where status = ?", Seq(status))
      Wire.page(rows.map(row => Json.toJson(row)), page, size, total)
    }
    Ok(payload)
  }

  def resolveReport(id: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.requireRole(request, "moderator")
    val uuid = parseUuid(id)
    val input = InputJson.read[ResolutionInput](request)
    val resolution = input.resolution
    val note = input.note
    val row = db.transaction { conn =>
      if (resolution == "actioned") {
        val bookmarkId = db
          .one(conn, "select bookmark_id from reports where id = ?", Seq(uuid))(
            _.getObject("bookmark_id", classOf[UUID])
          )
          .getOrElse(throw new NotFoundProblem)
        db.one(conn, "select id from bookmarks where id = ? for update", Seq(bookmarkId))(_ => true)
      }
      val report = db
        .one(conn, "select * from reports where id = ? for update", Seq(uuid))(Rows.report)
        .getOrElse(throw new NotFoundProblem)
      if (resolution == "open") {
        val conflict = db
          .one(
            conn,
            "select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open' and id <> ?",
            Seq(report.bookmarkId, report.reporter, uuid)
          )(_ => true)
          .getOrElse(false)
        if (conflict) throw new ConflictProblem("The reporter already has another open report on this bookmark.")
        val reopened =
          try
            db.returning(
              conn,
              "update reports set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null where id = ? returning *",
              Seq(uuid)
            )(Rows.report)
          catch {
            case error: SQLException if SqlErrors.state(error).contains("23505") =>
              throw new ConflictProblem("The reporter already has another open report on this bookmark.")
          }
        audit.record(
          conn,
          caller.username,
          "report.reopened",
          "report",
          uuid.toString,
          Json.obj("bookmarkId" -> report.bookmarkId.toString)
        )
        logger.event(
          "info",
          "report_reopened",
          "success",
          "Report re-opened",
          "actor" -> JsString(caller.username),
          "resource_type" -> JsString("report"),
          "resource_id" -> JsString(uuid.toString),
          "bookmark_id" -> JsString(report.bookmarkId.toString)
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
    Ok(Json.toJson(row))
  }

  def setBookmarkStatus(id: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.requireRole(request, "moderator")
    val uuid = parseUuid(id)
    val input = InputJson.read[BookmarkStatusInput](request)
    val status = input.status
    val note = input.note
    val row = db.transaction { conn =>
      val existing = db
        .one(conn, "select * from bookmarks where id = ? for update", Seq(uuid))(Rows.bookmark)
        .getOrElse(throw new NotFoundProblem)
      val updated = db.returning(
        conn,
        "update bookmarks set status = ?, updated_at = ? where id = ? returning *",
        Seq(status, Instant.now(), uuid)
      )(Rows.bookmark)
      audit.record(
        conn,
        caller.username,
        "bookmark.status-changed",
        "bookmark",
        uuid.toString,
        Json.obj("from" -> existing.status, "to" -> status, "note" -> note)
      )
      logger.event(
        "info",
        "bookmark_status_changed",
        "success",
        "Bookmark moderation status changed",
        "actor" -> JsString(caller.username),
        "resource_type" -> JsString("bookmark"),
        "resource_id" -> JsString(uuid.toString),
        "from" -> JsString(existing.status),
        "to" -> JsString(status)
      )
      updated
    }
    Ok(Json.toJson(row))
  }

  private def count(conn: Connection, sql: String, params: Seq[Any] = Seq.empty): Long =
    db.one(conn, sql, params)(_.getLong("count")).getOrElse(0L)

  private def findBookmark(conn: Connection, id: UUID): Option[BookmarkRow] =
    db.one(conn, "select * from bookmarks where id = ?", Seq(id))(Rows.bookmark)

  private def ownReport(conn: Connection, reporter: String, id: UUID): ReportRow = {
    val row = db
      .one(conn, "select * from reports where id = ? for update", Seq(id))(Rows.report)
      .getOrElse(throw new NotFoundProblem)
    if (row.reporter != reporter) throw new NotFoundProblem
    row
  }

  private def requireOpen(report: ReportRow): Unit =
    if (report.status != "open") throw new ConflictProblem("The report has already been resolved.")

  private def validatedReportStatus(value: Option[String]): Option[String] =
    value.map { status =>
      if (!Seq("open", "dismissed", "actioned").contains(status))
        throw new BadRequestProblem(s"unknown status: $status")
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
    audit.record(
      conn,
      actor,
      "report.resolved",
      "report",
      report.id.toString,
      Json.obj(
        "bookmarkId" -> report.bookmarkId.toString,
        "resolution" -> resolution,
        "note" -> note,
        "autoResolved" -> autoResolved
      )
    )
    logger.event(
      "info",
      "report_resolved",
      "success",
      "Report resolved",
      "actor" -> JsString(actor),
      "resource_type" -> JsString("report"),
      "resource_id" -> JsString(report.id.toString),
      "bookmark_id" -> JsString(report.bookmarkId.toString),
      "resolution" -> JsString(resolution),
      "auto_resolved" -> JsBoolean(autoResolved)
    )
    updated
  }

  private def hideBookmark(conn: Connection, actor: String, bookmarkId: UUID, note: Option[String]): Unit = {
    val bookmark = findBookmark(conn, bookmarkId).getOrElse(throw new NotFoundProblem)
    if (bookmark.status == "hidden") return
    db.execute(
      conn,
      "update bookmarks set status = 'hidden', updated_at = ? where id = ?",
      Seq(Instant.now(), bookmarkId)
    )
    audit.record(
      conn,
      actor,
      "bookmark.status-changed",
      "bookmark",
      bookmarkId.toString,
      Json.obj("from" -> "active", "to" -> "hidden", "note" -> note)
    )
    logger.event(
      "info",
      "bookmark_status_changed",
      "success",
      "Bookmark hidden by an actioned report",
      "actor" -> JsString(actor),
      "resource_type" -> JsString("bookmark"),
      "resource_id" -> JsString(bookmarkId.toString),
      "from" -> JsString("active"),
      "to" -> JsString("hidden")
    )
  }
}
