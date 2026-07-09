package controllers

import models.*
import play.api.libs.json.{JsArray, JsString, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import repositories.{Db, Rows}
import services.{ApiAction, AuthService}
import support.{BookmarkInput, CursorCodec, InputJson, Responses, Wire}
import support.InputJson.given
import support.Responses.given

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Singleton}

@Singleton
class BookmarkController @Inject() (
    cc: ControllerComponents,
    api: ApiAction,
    db: Db,
    auth: AuthService
) extends AbstractController(cc) {
  import Wire.*

  private val TagPattern = "^[a-z0-9-]{1,30}$".r

  def listV1: Action[AnyContent] = api { implicit request =>
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
      Wire.page(rows.map(row => Json.toJson(row)), page, size, total)
    }
    Ok(payload).withHeaders(
      "Deprecation" -> "@1782864000",
      "Sunset" -> "Thu, 01 Jul 2027 00:00:00 GMT",
      "Link" -> "</api/v2/bookmarks>; rel=\"successor-version\""
    )
  }

  def listV2: Action[AnyContent] = api { implicit request =>
    val caller = auth.optional(request)
    val (_, size) = paging(request.queryString)
    val filters = parseListFilters(request.queryString)
    val cursor = single(request.queryString, "cursor").map(CursorCodec.decode)
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
        "items" -> Some(JsArray(items.map(row => Json.toJson(row)))),
        "nextCursor" -> nextCursor.map(JsString.apply)
      )
    }
    Ok(payload)
  }

  def create: Action[AnyContent] = api { implicit request =>
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
    Created(Json.toJson(row)).withHeaders("Location" -> s"/api/v1/bookmarks/$id")
  }

  def get(id: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.optional(request)
    val uuid = parseUuid(id)
    val row = db.withConnection(conn => findBookmark(conn, uuid))
    val visible = row.exists(bookmark =>
      bookmark.owner == caller
        .map(_.username)
        .orNull || (bookmark.visibility == "public" && bookmark.status == "active")
    )
    if (!visible) throw new NotFoundProblem
    Ok(Json.toJson(row.get))
  }

  def update(id: String): Action[AnyContent] = api { implicit request =>
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
    Ok(Json.toJson(row))
  }

  def delete(id: String): Action[AnyContent] = api { implicit request =>
    val caller = auth.requireCaller(request)
    val uuid = parseUuid(id)
    db.withConnection { conn =>
      val bookmark = findBookmark(conn, uuid).filter(_.owner == caller.username).getOrElse(throw new NotFoundProblem)
      db.execute(conn, "delete from bookmarks where id = ?", Seq(bookmark.id))
    }
    NoContent
  }

  def tags: Action[AnyContent] = api { implicit request =>
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

  private def count(conn: Connection, sql: String, params: Seq[Any] = Seq.empty): Long =
    db.one(conn, sql, params)(_.getLong("count")).getOrElse(0L)

  private def findBookmark(conn: Connection, id: UUID): Option[BookmarkRow] =
    db.one(conn, "select * from bookmarks where id = ?", Seq(id))(Rows.bookmark)
}
