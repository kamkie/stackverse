package services

import models._
import play.api.libs.json._
import play.api.mvc._
import repositories.{Db, Rows}
import support.{
  BookmarkInput,
  BookmarkStatusInput,
  CursorCodec,
  InputJson,
  MessageInput,
  ReportInput,
  ResolutionInput,
  Responses,
  UserStatusInput,
  Wire
}
import support.InputJson.given

import java.sql.{Connection, SQLException}
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID
import javax.inject._
import scala.concurrent.Future
import scala.util.Try

@Singleton
class StackverseActions @Inject() (
    cc: ControllerComponents,
    db: Db,
    auth: AuthService,
    i18n: I18n,
    logger: EventLogger,
    databaseExecutionContext: DatabaseExecutionContext
) extends AbstractController(cc) {
  import Wire._

  private val TagPattern = "^[a-z0-9-]{1,30}$".r
  private val UtcDayMillis = 24L * 60L * 60L * 1000L

  def healthz: Action[AnyContent] = Action {
    Ok(Json.obj("status" -> "up"))
  }

  def readyz: Action[AnyContent] = Action.async {
    Future {
      val started = System.nanoTime()
      try {
        db.withConnection(conn => db.one(conn, "select 1")(_.getInt(1)))
        Ok(Json.obj("status" -> "ready"))
      } catch {
        case error: Throwable =>
          logger.event(
            "warn",
            "dependency_call_failed",
            "failure",
            "Readiness lost: database unreachable",
            "dependency" -> JsString("postgres"),
            "duration_ms" -> JsNumber((System.nanoTime() - started) / 1000000),
            "error_code" -> JsString(sqlState(error).getOrElse("connection_error"))
          )
          Status(503)(Json.obj("status" -> "unavailable"))
      }
    }(using databaseExecutionContext)
  }

  def me: Action[AnyContent] = api { implicit request =>
    Ok(auth.me(auth.requireCaller(request)))
  }

  def listBookmarksV1: Action[AnyContent] = api { implicit request =>
    val caller = auth.optional(request)
    val (page, size) = paging(request.queryString)
    val filters = parseListFilters(request.queryString)
    val (where, params) = listingWhere(caller, filters)
    val payload = db.withConnection { conn =>
      val rows = db.query(
        conn,
        s"""select * from bookmarks where $where
           |order by created_at desc, id desc
           |limit ? offset ?""".stripMargin,
        params ++ Seq(size, page * size)
      )(Rows.bookmark)
      val total = count(conn, s"select count(*) as count from bookmarks where $where", params)
      pagePayload(rows.map(Responses.bookmark), page, size, total)
    }
    Ok(payload).withHeaders(
      "Deprecation" -> "@1782864000",
      "Sunset" -> "Thu, 01 Jul 2027 00:00:00 GMT",
      "Link" -> "</api/v2/bookmarks>; rel=\"successor-version\""
    )
  }

  def listBookmarksV2: Action[AnyContent] = api { implicit request =>
    val caller = auth.optional(request)
    val (_, size) = paging(request.queryString)
    val filters = parseListFilters(request.queryString)
    val rawCursor = single(request.queryString, "cursor")
    val cursor = rawCursor.map(CursorCodec.decode)
    val (baseWhere, baseParams) = listingWhere(caller, filters)
    val (where, params) = cursor match {
      case Some(value) =>
        (
          s"$baseWhere and (created_at < ? or (created_at = ? and id < ?))",
          baseParams ++ Seq(value.createdAt, value.createdAt, value.id)
        )
      case None => (baseWhere, baseParams)
    }
    val payload = db.withConnection { conn =>
      val fetched = db.query(
        conn,
        s"""select * from bookmarks where $where
           |order by created_at desc, id desc
           |limit ?""".stripMargin,
        params :+ (size + 1)
      )(Rows.bookmark)
      val items = fetched.take(size)
      val nextCursor = if (fetched.size > size && items.nonEmpty) {
        val last = items.last
        Some(CursorCodec.encode(BookmarkCursor(last.createdAt, last.id)))
      } else None
      Wire.obj(
        "items" -> Some(JsArray(items.map(Responses.bookmark))),
        "nextCursor" -> nextCursor.map(JsString.apply)
      )
    }
    Ok(payload)
  }

  def createBookmark: Action[AnyContent] = api { implicit request =>
    val caller = auth.requireCaller(request)
    val input = InputJson.read[BookmarkInput](request)
    val now = Instant.now()
    val id = UUID.randomUUID()
    val row = db.withConnection { conn =>
      db.returning(
        conn,
        """insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)
          |values (?, ?, ?, ?, ?, ?::text[], ?, 'active', ?, ?) returning *""".stripMargin,
        Seq(id, caller.username, input.url, input.title, input.notes, input.tags, input.visibility, now, now)
      )(Rows.bookmark)
    }
    Created(Responses.bookmark(row)).withHeaders("Location" -> s"/api/v1/bookmarks/$id")
  }

  def getBookmark(id: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.optional(request)
    val uuid = parseUuid(id)
    val row = db.withConnection(conn => findBookmark(conn, uuid))
    val visible = row.exists(bookmark =>
      bookmark.owner == caller
        .map(_.username)
        .orNull || (bookmark.visibility == "public" && bookmark.status == "active")
    )
    if (!visible) throw new NotFoundProblem
    Ok(Responses.bookmark(row.get))
  }

  def updateBookmark(id: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.requireCaller(request)
    val uuid = parseUuid(id)
    val input = InputJson.read[BookmarkInput](request)
    val row = db.transaction { conn =>
      val existing = db.one(conn, "select * from bookmarks where id = ? for update", Seq(uuid))(Rows.bookmark)
      val bookmark = existing.filter(_.owner == caller.username).getOrElse(throw new NotFoundProblem)
      if (bookmark.status == "hidden" && input.visibility == "public") {
        throw new ConflictProblem(
          "This bookmark was hidden by moderation and cannot be made public.",
          Some("error.bookmark.hidden-publish")
        )
      }
      db.returning(
        conn,
        """update bookmarks set url = ?, title = ?, notes = ?, tags = ?::text[], visibility = ?, updated_at = ?
          |where id = ? returning *""".stripMargin,
        Seq(input.url, input.title, input.notes, input.tags, input.visibility, Instant.now(), uuid)
      )(Rows.bookmark)
    }
    Ok(Responses.bookmark(row))
  }

  def deleteBookmark(id: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.requireCaller(request)
    val uuid = parseUuid(id)
    db.withConnection { conn =>
      val bookmark = findBookmark(conn, uuid).filter(_.owner == caller.username).getOrElse(throw new NotFoundProblem)
      db.execute(conn, "delete from bookmarks where id = ?", Seq(bookmark.id))
    }
    NoContent
  }

  def listTags: Action[AnyContent] = api { implicit request =>
    val caller = auth.requireCaller(request)
    val tags = db.withConnection { conn =>
      db.query(
        conn,
        """select tag, count(*) as count
          |from bookmarks, unnest(tags) as tag
          |where owner = ?
          |group by tag
          |order by count desc, tag asc""".stripMargin,
        Seq(caller.username)
      )(rs => Json.obj("tag" -> rs.getString("tag"), "count" -> rs.getLong("count")))
    }
    Ok(Json.obj("tags" -> tags))
  }

  def listMessages: Action[AnyContent] = api { implicit request =>
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
      pagePayload(rows.map(Responses.message), page, size, total)
    }
    withEtag(request, payload)
  }

  def messageBundle: Action[AnyContent] = api { implicit request =>
    auth.optional(request)
    val language = requestLanguage(request)
    withEtag(
      request,
      Json.obj("language" -> language, "messages" -> i18n.bundle(language)),
      "Content-Language" -> language
    )
  }

  def getMessage(id: String): Action[AnyContent] = api { implicit request =>
    auth.optional(request)
    val uuid = parseUuid(id)
    val row = db.withConnection { conn =>
      db.one(conn, "select * from messages where id = ?", Seq(uuid))(Rows.message).getOrElse(throw new NotFoundProblem)
    }
    withEtag(request, Responses.message(row))
  }

  def createMessage: Action[AnyContent] = api { implicit request =>
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
          case error: SQLException if sqlState(error).contains("23505") => throw duplicateMessage(input)
        }
      recordAudit(conn, caller.username, "message.created", "message", inserted.id.toString, messageSnapshot(inserted))
      inserted
    }
    logMessage("message_created", "Message created", caller.username, row)
    Created(Responses.message(row)).withHeaders("Location" -> s"/api/v1/messages/${row.id}")
  }

  def updateMessage(id: String): Action[AnyContent] = api { implicit request =>
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
          case error: SQLException if sqlState(error).contains("23505") => throw duplicateMessage(input)
        }
      recordAudit(conn, caller.username, "message.updated", "message", updated.id.toString, messageSnapshot(updated))
      updated
    }
    logMessage("message_updated", "Message updated", caller.username, row)
    Ok(Responses.message(row))
  }

  def deleteMessage(id: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.requireRole(request, "admin")
    val uuid = parseUuid(id)
    val row = db.transaction { conn =>
      val deleted = db
        .one(conn, "delete from messages where id = ? returning *", Seq(uuid))(Rows.message)
        .getOrElse(throw new NotFoundProblem)
      recordAudit(conn, caller.username, "message.deleted", "message", deleted.id.toString, messageSnapshot(deleted))
      deleted
    }
    logMessage("message_deleted", "Message deleted", caller.username, row)
    NoContent
  }

  def createReport(id: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.requireCaller(request)
    val bookmarkId = parseUuid(id)
    val input = InputJson.read[ReportInput](request)
    val row = db.transaction { conn =>
      val visible = db
        .one(conn, "select visibility, status from bookmarks where id = ? for update", Seq(bookmarkId)) { rs =>
          rs.getString("visibility") == "public" && rs.getString("status") == "active"
        }
        .getOrElse(false)
      if (!visible) throw new NotFoundProblem
      val open = db
        .one(
          conn,
          "select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open'",
          Seq(bookmarkId, caller.username)
        )(_ => true)
        .getOrElse(false)
      if (open) throw new ConflictProblem("You already have an open report on this bookmark.")
      try
        db.returning(
          conn,
          """insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
            |values (?, ?, ?, ?, ?, 'open', ?) returning *""".stripMargin,
          Seq(UUID.randomUUID(), bookmarkId, caller.username, input.reason, input.comment, Instant.now())
        )(Rows.report)
      catch {
        case error: SQLException if sqlState(error).contains("23505") =>
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
      "bookmark_id" -> JsString(bookmarkId.toString),
      "reason" -> JsString(row.reason)
    )
    Created(Responses.report(row))
  }

  def listMyReports: Action[AnyContent] = api { implicit request =>
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
      pagePayload(rows.map(Responses.report), page, size, total)
    }
    Ok(payload)
  }

  def updateMyReport(id: String): Action[AnyContent] = api { implicit request =>
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
    Ok(Responses.report(row))
  }

  def withdrawReport(id: String): Action[AnyContent] = api { implicit request =>
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
      pagePayload(rows.map(Responses.report), page, size, total)
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
            case error: SQLException if sqlState(error).contains("23505") =>
              throw new ConflictProblem("The reporter already has another open report on this bookmark.")
          }
        recordAudit(
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
    Ok(Responses.report(row))
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
      recordAudit(
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
    Ok(Responses.bookmark(row))
  }

  def listUsers: Action[AnyContent] = api { implicit request =>
    auth.requireRole(request, "admin")
    val (page, size) = paging(request.queryString)
    val q = single(request.queryString, "q")
    maxLength(q, 100, "q")
    val status = single(request.queryString, "status")
    if (status.exists(value => value != "active" && value != "blocked"))
      throw new BadRequestProblem(s"unknown status: ${status.get}")
    val clauses = scala.collection.mutable.ArrayBuffer("true")
    val params = scala.collection.mutable.ArrayBuffer.empty[Any]
    q.filter(_.trim.nonEmpty).foreach { value =>
      clauses += "u.username ilike ? escape '\\'"
      params += s"%${escapeLike(value)}%"
    }
    status.foreach { value =>
      clauses += "u.status = ?"
      params += value
    }
    val where = clauses.mkString(" and ")
    val payload = db.withConnection { conn =>
      val rows = db.query(
        conn,
        s"""$WithBookmarkCount where $where
           |order by u.last_seen desc, u.username asc limit ? offset ?""".stripMargin,
        params.toSeq ++ Seq(size, page * size)
      )(Rows.user)
      val total = count(conn, s"select count(*) as count from user_accounts u where $where", params.toSeq)
      pagePayload(rows.map(Responses.user), page, size, total)
    }
    Ok(payload)
  }

  def getUser(username: String): Action[AnyContent] = api { implicit request =>
    auth.requireRole(request, "admin")
    val row = db.withConnection(conn => findUser(conn, username).getOrElse(throw new NotFoundProblem))
    Ok(Responses.user(row))
  }

  def setUserStatus(username: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.requireRole(request, "admin")
    val input = InputJson.read[UserStatusInput](request)
    val status = input.status
    val reason = input.reason
    if (status == "blocked") {
      if (username == caller.username) throw new ConflictProblem("Admins cannot block themselves.")
    }
    db.transaction { conn =>
      db.one(conn, "select username from user_accounts where username = ? for update", Seq(username))(_ => true)
        .getOrElse(throw new NotFoundProblem)
      if (status == "blocked") {
        db.execute(
          conn,
          "update user_accounts set status = 'blocked', blocked_reason = ? where username = ?",
          Seq(reason, username)
        )
        recordAudit(conn, caller.username, "user.blocked", "user", username, Json.obj("reason" -> reason))
      } else {
        db.execute(
          conn,
          "update user_accounts set status = 'active', blocked_reason = null where username = ?",
          Seq(username)
        )
        recordAudit(conn, caller.username, "user.unblocked", "user", username)
      }
    }
    logger.event(
      "info",
      if (status == "blocked") "user_blocked" else "user_unblocked",
      "success",
      if (status == "blocked") "User account blocked" else "User account unblocked",
      "actor" -> JsString(caller.username),
      "resource_type" -> JsString("user"),
      "resource_id" -> JsString(username)
    )
    val row = db.withConnection(conn => findUser(conn, username).getOrElse(throw new NotFoundProblem))
    Ok(Responses.user(row))
  }

  def auditLog: Action[AnyContent] = api { implicit request =>
    auth.requireRole(request, "admin")
    val (page, size) = paging(request.queryString)
    val clauses = scala.collection.mutable.ArrayBuffer("true")
    val params = scala.collection.mutable.ArrayBuffer.empty[Any]
    Seq("actor" -> "actor", "action" -> "action", "targetType" -> "target_type", "targetId" -> "target_id").foreach {
      case (param, column) =>
        single(request.queryString, param).foreach { value =>
          clauses += s"$column = ?"
          params += value
        }
    }
    single(request.queryString, "from").foreach { value =>
      clauses += "created_at >= ?"
      params += parseInstantParam(value, "from")
    }
    single(request.queryString, "to").foreach { value =>
      clauses += "created_at <= ?"
      params += parseInstantParam(value, "to")
    }
    val where = clauses.mkString(" and ")
    val payload = db.withConnection { conn =>
      val rows = db.query(
        conn,
        s"select * from audit_entries where $where order by created_at desc, id desc limit ? offset ?",
        params.toSeq ++ Seq(size, page * size)
      )(Rows.audit)
      val total = count(conn, s"select count(*) as count from audit_entries where $where", params.toSeq)
      pagePayload(rows.map(Responses.audit), page, size, total)
    }
    Ok(payload)
  }

  def stats: Action[AnyContent] = api { implicit request =>
    auth.requireRole(request, "moderator")
    val today = LocalDate.now(ZoneOffset.UTC)
    val from = today.minusDays(29).atStartOfDay().toInstant(ZoneOffset.UTC)
    val payload = db.withConnection { conn =>
      val users = count(conn, "select count(*) as count from user_accounts")
      val bookmarks = count(conn, "select count(*) as count from bookmarks")
      val publicBookmarks = count(conn, "select count(*) as count from bookmarks where visibility = 'public'")
      val hiddenBookmarks = count(conn, "select count(*) as count from bookmarks where status = 'hidden'")
      val openReports = count(conn, "select count(*) as count from reports where status = 'open'")
      val createdPerDay = countPerDay(conn, "bookmarks", "created_at", from)
      val activePerDay = countPerDay(conn, "user_accounts", "last_seen", from)
      val topTags = db.query(
        conn,
        """select tag, count(*) as count from bookmarks, unnest(tags) as tag
          |group by tag order by count desc, tag asc limit 10""".stripMargin
      )(rs => Json.obj("tag" -> rs.getString("tag"), "count" -> rs.getLong("count")))
      val daily = (0 until 30).map { offset =>
        val date = today.minusDays(29 - offset.toLong)
        val bookmarksCreated = createdPerDay.get(date.toString).getOrElse(0L)
        val activeUsers = activePerDay.get(date.toString).getOrElse(0L)
        Json.obj(
          "date" -> date.toString,
          "bookmarksCreated" -> JsNumber(BigDecimal(bookmarksCreated)),
          "activeUsers" -> JsNumber(BigDecimal(activeUsers))
        )
      }
      Json.obj(
        "totals" -> Json.obj(
          "users" -> users,
          "bookmarks" -> bookmarks,
          "publicBookmarks" -> publicBookmarks,
          "hiddenBookmarks" -> hiddenBookmarks,
          "openReports" -> openReports
        ),
        "daily" -> JsArray(daily),
        "topTags" -> topTags
      )
    }
    withEtag(request, payload)
  }

  private def api(block: Request[AnyContent] => Result): Action[AnyContent] = Action.async { request =>
    Future {
      try
        block(request)
      catch {
        case problem: ValidationProblem =>
          logger.event(
            "info",
            "input_validation_failed",
            "failure",
            "Request validation failed",
            "error_code" -> JsString("validation_failed"),
            "fields" -> JsString(problem.violations.map(_.field).mkString(","))
          )
          val language = requestLanguage(request)
          val errors = problem.violations.map { violation =>
            Json.obj(
              "field" -> violation.field,
              "messageKey" -> violation.messageKey,
              "message" -> i18n.localize(violation.messageKey, language)
            )
          }
          Wire.problem(400, "Bad Request", Some("Request validation failed."), Some(errors))
        case problem: ApiProblem =>
          val detail = problem.detailKey.map(key => i18n.localize(key, requestLanguage(request))).orElse(problem.detail)
          Wire.problem(problem.status, problem.title, detail)
        case error: Throwable =>
          sqlState(error).foreach { state =>
            logger.event(
              "error",
              "dependency_call_failed",
              "failure",
              "PostgreSQL call failed during a request",
              "dependency" -> JsString("postgres"),
              "error_code" -> JsString(state)
            )
          }
          Wire.problem(500, "Internal Server Error", Some("An unexpected error occurred."))
      }
    }(using databaseExecutionContext)
  }

  private case class ListFilters(tags: Seq[String], q: Option[String], visibility: Option[String])

  private def parseListFilters(query: Map[String, Seq[String]]): ListFilters = {
    val q = single(query, "q")
    maxLength(q, 200, "q")
    val visibility = single(query, "visibility")
    if (visibility.exists(value => value != "private" && value != "public"))
      throw new BadRequestProblem(s"unknown visibility: ${visibility.get}")
    ListFilters(validateQueryTags(multi(query, "tag")), q, visibility)
  }

  private def validateQueryTags(values: Seq[String]): Seq[String] = {
    val tags = values.map(_.trim.toLowerCase)
    val validator = new Validator()
    validator.check(tags.forall(tag => TagPattern.matches(tag)), "tag", "validation.tag.invalid")
    validator.throwIfInvalid()
    tags
  }

  private def listingWhere(caller: Option[Caller], filters: ListFilters): (String, Seq[Any]) = {
    val clauses = scala.collection.mutable.ArrayBuffer.empty[String]
    val params = scala.collection.mutable.ArrayBuffer.empty[Any]
    if (filters.visibility.contains("public")) {
      clauses += "visibility = 'public' and status = 'active'"
    } else {
      val username = caller.map(_.username).getOrElse(throw new UnauthorizedProblem)
      clauses += "owner = ?"
      params += username
      filters.visibility.foreach { value =>
        clauses += "visibility = ?"
        params += value
      }
    }
    if (filters.tags.nonEmpty) {
      clauses += "tags @> ?::text[]"
      params += filters.tags
    }
    filters.q.filter(_.trim.nonEmpty).foreach { value =>
      val pattern = s"%${escapeLike(value)}%"
      clauses += "(title ilike ? escape '\\' or notes ilike ? escape '\\')"
      params += pattern
      params += pattern
    }
    (clauses.mkString(" and "), params.toSeq)
  }

  private def requestLanguage(request: RequestHeader): String =
    i18n.resolve(first(request.queryString, "lang"), request.headers.get("Accept-Language"))

  private def pagePayload(items: Seq[JsValue], page: Int, size: Int, totalItems: Long): JsObject =
    Json.obj(
      "items" -> items,
      "page" -> page,
      "size" -> size,
      "totalItems" -> totalItems,
      "totalPages" -> math.ceil(totalItems.toDouble / size.toDouble).toInt
    )

  private def count(conn: Connection, sql: String, params: Seq[Any] = Seq.empty): Long =
    db.one(conn, sql, params)(_.getLong("count")).getOrElse(0L)

  private def findBookmark(conn: Connection, id: UUID): Option[BookmarkRow] =
    db.one(conn, "select * from bookmarks where id = ?", Seq(id))(Rows.bookmark)

  private val WithBookmarkCount =
    """select u.*, (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count
      |from user_accounts u""".stripMargin

  private def findUser(conn: Connection, username: String): Option[UserAccountRow] =
    db.one(conn, s"$WithBookmarkCount where u.username = ?", Seq(username))(Rows.user)

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

  private def recordAudit(
      conn: Connection,
      actor: String,
      action: String,
      targetType: String,
      targetId: String,
      detail: JsObject = Json.obj()
  ): Unit =
    db.execute(
      conn,
      "insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at) values (?, ?, ?, ?, ?, ?, ?)",
      Seq(
        UUID.randomUUID(),
        actor,
        action,
        targetType,
        targetId,
        if (detail.fields.isEmpty) None else Some(detail),
        Instant.now()
      )
    )

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
    recordAudit(
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
    recordAudit(
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

  private def countPerDay(conn: Connection, table: String, column: String, from: Instant): Map[String, Long] =
    db.query(
      conn,
      s"select ($column at time zone 'UTC')::date::text as day, count(*) as count from $table where $column >= ? group by day",
      Seq(from)
    )(rs => rs.getString("day") -> rs.getLong("count"))
      .toMap

  private def parseInstantParam(value: String, name: String): Instant =
    Try(Instant.parse(value)).getOrElse(throw new BadRequestProblem(s"$name must be an RFC 3339 date-time"))

  private def sqlState(error: Throwable): Option[String] = error match {
    case sql: SQLException => Option(sql.getSQLState).orElse(Option(sql.getCause).flatMap(sqlState))
    case other             => Option(other.getCause).flatMap(sqlState)
  }
}
