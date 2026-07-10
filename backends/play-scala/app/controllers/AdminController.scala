package controllers

import models.*
import play.api.libs.json.{JsArray, JsNumber, JsString, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import repositories.{Db, Rows}
import services.{ApiAction, AuditService, EventLogger}
import support.{InputJson, Responses, UserStatusInput, Wire}
import support.InputJson.given
import support.Responses.given

import java.sql.Connection
import java.time.{Instant, LocalDate, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.util.Try

@Singleton
class AdminController @Inject() (
    cc: ControllerComponents,
    api: ApiAction,
    db: Db,
    logger: EventLogger,
    audit: AuditService
) extends AbstractController(cc) {
  import Wire.*

  private val WithBookmarkCount =
    """select u.*, (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count
      |from user_accounts u""".stripMargin

  def listUsers: Action[AnyContent] = api.withRole("admin") { implicit request =>
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
      val total = db.count(conn, s"select count(*) as count from user_accounts u where $where", params.toSeq)
      Wire.page(rows.map(row => Json.toJson(row)), page, size, total)
    }
    Ok(payload)
  }

  def getUser(username: String): Action[AnyContent] = api.withRole("admin") { implicit request =>
    val row = db.withConnection(conn => findUser(conn, username).getOrElse(throw new NotFoundProblem))
    Ok(Json.toJson(row))
  }

  def setUserStatus(username: String): Action[AnyContent] = api.withRole("admin") { implicit request =>
    val caller = request.caller
    val input = InputJson.read[UserStatusInput](request)
    val status = input.status
    val reason = input.reason
    if (status == "blocked" && username == caller.username) throw new ConflictProblem("Admins cannot block themselves.")
    db.transaction { conn =>
      db.one(conn, "select username from user_accounts where username = ? for update", Seq(username))(_ => true)
        .getOrElse(throw new NotFoundProblem)
      if (status == "blocked") {
        db.execute(
          conn,
          "update user_accounts set status = 'blocked', blocked_reason = ? where username = ?",
          Seq(reason, username)
        )
        audit.record(conn, caller.username, "user.blocked", "user", username, Json.obj("reason" -> reason))
      } else {
        db.execute(
          conn,
          "update user_accounts set status = 'active', blocked_reason = null where username = ?",
          Seq(username)
        )
        audit.record(conn, caller.username, "user.unblocked", "user", username)
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
    Ok(Json.toJson(row))
  }

  def auditLog: Action[AnyContent] = api.withRole("admin") { implicit request =>
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
      val total = db.count(conn, s"select count(*) as count from audit_entries where $where", params.toSeq)
      Wire.page(rows.map(row => Json.toJson(row)), page, size, total)
    }
    Ok(payload)
  }

  def stats: Action[AnyContent] = api.withRole("moderator") { implicit request =>
    val today = LocalDate.now(ZoneOffset.UTC)
    val from = today.minusDays(29).atStartOfDay().toInstant(ZoneOffset.UTC)
    val payload = db.withConnection { conn =>
      val users = db.count(conn, "select count(*) as count from user_accounts")
      val bookmarks = db.count(conn, "select count(*) as count from bookmarks")
      val publicBookmarks = db.count(conn, "select count(*) as count from bookmarks where visibility = 'public'")
      val hiddenBookmarks = db.count(conn, "select count(*) as count from bookmarks where status = 'hidden'")
      val openReports = db.count(conn, "select count(*) as count from reports where status = 'open'")
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

  private def findUser(conn: Connection, username: String): Option[UserAccountRow] =
    db.one(conn, s"$WithBookmarkCount where u.username = ?", Seq(username))(Rows.user)

  private def countPerDay(conn: Connection, table: String, column: String, from: Instant): Map[String, Long] =
    db.query(
      conn,
      s"select ($column at time zone 'UTC')::date::text as day, count(*) as count from $table where $column >= ? group by day",
      Seq(from)
    )(rs => rs.getString("day") -> rs.getLong("count"))
      .toMap

  private def parseInstantParam(value: String, name: String): Instant =
    Try(Instant.parse(value)).getOrElse(throw new BadRequestProblem(s"$name must be an RFC 3339 date-time"))
}
