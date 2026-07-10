package dev.stackverse.http4s

import cats.effect.IO
import io.circe.{Json, JsonObject}
import org.http4s.*

import java.sql.{Connection, SQLException}
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

trait AdminOperations {
  def listUsers(req: Request[IO]): IO[Response[IO]]
  def getUser(req: Request[IO], username: String): IO[Response[IO]]
  def setUserStatus(req: Request[IO], username: String): IO[Response[IO]]
  def auditLog(req: Request[IO]): IO[Response[IO]]
  def stats(req: Request[IO]): IO[Response[IO]]
}

final class AdminService(db: Db, auth: AuthService, logger: EventLogger, repository: SharedRepository)
    extends AdminOperations {
  import RouteSupport.*
  import Wire.*
  import repository.*

  override def listUsers(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    auth.requireRole(req, "admin")
    val (page, size) = paging(query(req))
    val q = single(query(req), "q")
    maxLength(q, 100, "q")
    val status = single(query(req), "status")
    if (status.exists(value => value != "active" && value != "blocked"))
      throw BadRequestProblem(s"unknown status: ${status.get}")
    val clauses = ArrayBuffer("true")
    val params = ArrayBuffer.empty[Any]
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
        s"$withBookmarkCountQuery where $where order by u.last_seen desc, u.username asc limit ? offset ?",
        params.toSeq ++ Seq(size, page * size)
      )(Rows.user)
      val total = count(conn, s"select count(*) as count from user_accounts u where $where", params.toSeq)
      pagePayload(rows.map(Responses.user), page, size, total)
    }
    jsonResponse(Status.Ok, payload)
  }

  override def getUser(req: Request[IO], username: String): IO[Response[IO]] = IO.blocking {
    auth.requireRole(req, "admin")
    val row = db.withConnection(conn => findUser(conn, username).getOrElse(throw NotFoundProblem()))
    jsonResponse(Status.Ok, Responses.user(row))
  }

  override def setUserStatus(req: Request[IO], username: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireRole(req, "admin")
        val status = body("status").flatMap(_.asString).getOrElse(throw BadRequestProblem("status is required"))
        if (status != "active" && status != "blocked") throw BadRequestProblem("status is required")
        val reason = body("reason").flatMap(_.asString).map(_.trim)
        if (status == "blocked") {
          val validator = Validator()
          validator.check(reason.exists(_.nonEmpty), "reason", "validation.block.reason.required")
          validator.check(reason.forall(_.length <= 1000), "reason", "validation.block.reason.too-long")
          validator.throwIfInvalid()
          if (username == caller.username) throw ConflictProblem("Admins cannot block themselves.")
        }
        db.transaction { conn =>
          db.one(conn, "select username from user_accounts where username = ? for update", Seq(username))(_ => true)
            .getOrElse(throw NotFoundProblem())
          if (status == "blocked") {
            db.execute(
              conn,
              "update user_accounts set status = 'blocked', blocked_reason = ? where username = ?",
              Seq(reason, username)
            )
            recordAudit(
              conn,
              caller.username,
              "user.blocked",
              "user",
              username,
              JsonObject("reason" -> reason.fold(Json.Null)(Json.fromString))
            )
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
          "actor" -> Json.fromString(caller.username),
          "resource_type" -> Json.fromString("user"),
          "resource_id" -> Json.fromString(username)
        )
        val row = db.withConnection(conn => findUser(conn, username).getOrElse(throw NotFoundProblem()))
        jsonResponse(Status.Ok, Responses.user(row))
      }
    } yield response

  override def auditLog(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    auth.requireRole(req, "admin")
    val (page, size) = paging(query(req))
    val clauses = ArrayBuffer("true")
    val params = ArrayBuffer.empty[Any]
    Seq("actor" -> "actor", "action" -> "action", "targetType" -> "target_type", "targetId" -> "target_id").foreach {
      case (param, column) =>
        single(query(req), param).foreach { value =>
          clauses += s"$column = ?"
          params += value
        }
    }
    single(query(req), "from").foreach { value =>
      clauses += "created_at >= ?"
      params += parseInstantParam(value, "from")
    }
    single(query(req), "to").foreach { value =>
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
    jsonResponse(Status.Ok, payload)
  }

  override def stats(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    auth.requireRole(req, "moderator")
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
      )(rs => Json.obj("tag" -> Json.fromString(rs.getString("tag")), "count" -> Json.fromLong(rs.getLong("count"))))
      val daily = (0 until 30).map { offset =>
        val date = today.minusDays(29 - offset.toLong)
        val bookmarksCreated = createdPerDay.get(date.toString).getOrElse(0L)
        val activeUsers = activePerDay.get(date.toString).getOrElse(0L)
        Json.obj(
          "date" -> Json.fromString(date.toString),
          "bookmarksCreated" -> Json.fromLong(bookmarksCreated),
          "activeUsers" -> Json.fromLong(activeUsers)
        )
      }
      Json.obj(
        "totals" -> Json.obj(
          "users" -> Json.fromLong(users),
          "bookmarks" -> Json.fromLong(bookmarks),
          "publicBookmarks" -> Json.fromLong(publicBookmarks),
          "hiddenBookmarks" -> Json.fromLong(hiddenBookmarks),
          "openReports" -> Json.fromLong(openReports)
        ),
        "daily" -> Json.arr(daily*),
        "topTags" -> Json.arr(topTags*)
      )
    }
    withEtag(req, payload)
  }

}
