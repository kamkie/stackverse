package dev.stackverse.http4s

import cats.effect.IO
import io.circe.{Json, JsonObject}
import org.http4s.*

import java.sql.{Connection, SQLException}
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

trait BookmarkOperations {
  def listBookmarksV1(req: Request[IO]): IO[Response[IO]]
  def listBookmarksV2(req: Request[IO]): IO[Response[IO]]
  def createBookmark(req: Request[IO]): IO[Response[IO]]
  def getBookmark(req: Request[IO], id: String): IO[Response[IO]]
  def updateBookmark(req: Request[IO], id: String): IO[Response[IO]]
  def deleteBookmark(req: Request[IO], id: String): IO[Response[IO]]
  def listTags(req: Request[IO]): IO[Response[IO]]
}

final class BookmarkService(db: Db, auth: AuthService, repository: SharedRepository) extends BookmarkOperations {
  import InputValidation.*
  import RouteSupport.*
  import Wire.*
  import repository.*

  private val TagPattern = "^[a-z0-9-]{1,30}$".r

  override def listBookmarksV1(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    val caller = auth.optional(req)
    val (page, size) = paging(query(req))
    val filters = parseListFilters(query(req))
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
    jsonResponse(
      Status.Ok,
      payload,
      "Deprecation" -> "@1782864000",
      "Sunset" -> "Thu, 01 Jul 2027 00:00:00 GMT",
      "Link" -> "</api/v2/bookmarks>; rel=\"successor-version\""
    )
  }

  override def listBookmarksV2(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    val caller = auth.optional(req)
    val (_, size) = paging(query(req))
    val filters = parseListFilters(query(req))
    val cursor = single(query(req), "cursor").map(CursorCodec.decode)
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
      obj(
        "items" -> Some(Json.arr(items.map(Responses.bookmark)*)),
        "nextCursor" -> nextCursor.map(Json.fromString)
      )
    }
    jsonResponse(Status.Ok, payload)
  }

  override def createBookmark(req: Request[IO]): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireCaller(req)
        val input = validateBookmarkInput(body)
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
        jsonResponse(Status.Created, Responses.bookmark(row), "Location" -> s"/api/v1/bookmarks/$id")
      }
    } yield response

  override def getBookmark(req: Request[IO], id: String): IO[Response[IO]] = IO.blocking {
    val caller = auth.optional(req)
    val uuid = parseUuid(id)
    val row = db.withConnection(conn => findBookmark(conn, uuid))
    val visible = row.exists(bookmark =>
      bookmark.owner == caller
        .map(_.username)
        .orNull || (bookmark.visibility == "public" && bookmark.status == "active")
    )
    if (!visible) throw NotFoundProblem()
    jsonResponse(Status.Ok, Responses.bookmark(row.get))
  }

  override def updateBookmark(req: Request[IO], id: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireCaller(req)
        val uuid = parseUuid(id)
        val input = validateBookmarkInput(body)
        val row = db.transaction { conn =>
          val existing = db.one(conn, "select * from bookmarks where id = ? for update", Seq(uuid))(Rows.bookmark)
          val bookmark = existing.filter(_.owner == caller.username).getOrElse(throw NotFoundProblem())
          if (bookmark.status == "hidden" && input.visibility == "public") {
            throw ConflictProblem(
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
        jsonResponse(Status.Ok, Responses.bookmark(row))
      }
    } yield response

  override def deleteBookmark(req: Request[IO], id: String): IO[Response[IO]] = IO.blocking {
    val caller = auth.requireCaller(req)
    val uuid = parseUuid(id)
    db.withConnection { conn =>
      val bookmark = findBookmark(conn, uuid).filter(_.owner == caller.username).getOrElse(throw NotFoundProblem())
      db.execute(conn, "delete from bookmarks where id = ?", Seq(bookmark.id))
    }
    Response[IO](Status.NoContent)
  }

  override def listTags(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    val caller = auth.requireCaller(req)
    val tags = db.withConnection { conn =>
      db.query(
        conn,
        """select tag, count(*) as count
          |from bookmarks, unnest(tags) as tag
          |where owner = ?
          |group by tag
          |order by count desc, tag asc""".stripMargin,
        Seq(caller.username)
      )(rs => Json.obj("tag" -> Json.fromString(rs.getString("tag")), "count" -> Json.fromLong(rs.getLong("count"))))
    }
    jsonResponse(Status.Ok, Json.obj("tags" -> Json.arr(tags*)))
  }

  private def parseListFilters(queryParams: Map[String, Seq[String]]): ListFilters = {
    val q = single(queryParams, "q")
    maxLength(q, 200, "q")
    val visibility = single(queryParams, "visibility")
    if (visibility.exists(value => value != "private" && value != "public"))
      throw BadRequestProblem(s"unknown visibility: ${visibility.get}")
    ListFilters(validateQueryTags(multi(queryParams, "tag")), q, visibility)
  }

  private def validateQueryTags(values: Seq[String]): Seq[String] = {
    val tags = values.map(_.trim.toLowerCase)
    val validator = Validator()
    validator.check(tags.forall(tag => TagPattern.matches(tag)), "tag", "validation.tag.invalid")
    validator.throwIfInvalid()
    tags
  }

  private def listingWhere(caller: Option[Caller], filters: ListFilters): (String, Seq[Any]) = {
    val clauses = ArrayBuffer.empty[String]
    val params = ArrayBuffer.empty[Any]
    if (filters.visibility.contains("public")) {
      clauses += "visibility = 'public' and status = 'active'"
    } else {
      val username = caller.map(_.username).getOrElse(throw UnauthorizedProblem())
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

}
