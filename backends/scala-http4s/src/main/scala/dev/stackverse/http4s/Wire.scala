package dev.stackverse.http4s

import cats.effect.IO
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import org.http4s.*
import org.typelevel.ci.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.{Base64, UUID}
import scala.util.Try

object Wire {
  val JsonContentType = "application/json; charset=utf-8"
  val ProblemContentType = "application/problem+json"

  def obj(fields: (String, Option[Json])*): Json =
    Json.fromJsonObject(JsonObject.fromIterable(fields.collect { case (key, Some(value)) => key -> value }))

  def problemResponse(
      status: Int,
      title: String,
      detail: Option[String] = None,
      errors: Option[Seq[Json]] = None
  ): Response[IO] =
    jsonResponse(
      Status.fromInt(status).getOrElse(Status.InternalServerError),
      obj(
        "type" -> Some(Json.fromString("about:blank")),
        "title" -> Some(Json.fromString(title)),
        "status" -> Some(Json.fromInt(status)),
        "detail" -> detail.map(Json.fromString),
        "errors" -> errors.map(values => Json.arr(values*))
      ),
      contentType = ProblemContentType
    )

  def jsonResponse(status: Status, payload: Json, headers: (String, String)*): Response[IO] =
    jsonResponse(status, payload, JsonContentType, headers*)

  private def jsonResponse(
      status: Status,
      payload: Json,
      contentType: String,
      headers: (String, String)*
  ): Response[IO] =
    val bytes = payload.noSpaces.getBytes(StandardCharsets.UTF_8)
    Response[IO](
      status = status,
      headers = Headers((Seq("Content-Type" -> contentType) ++ headers).map { case (key, value) =>
        Header.Raw(CIString(key), value)
      }),
      body = Stream.emits(bytes).covary[IO]
    )

  def withEtag(request: Request[IO], payload: Json, headers: (String, String)*): Response[IO] = {
    val body = payload.noSpaces
    val digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))
    val etag = "\"" + Base64.getUrlEncoder.withoutPadding().encodeToString(digest) + "\""
    val cacheHeaders = Seq("ETag" -> etag, "Cache-Control" -> "no-cache") ++ headers
    val matches = header(request, "If-None-Match").exists(_.split(",").exists(_.trim == etag))
    if (matches) {
      Response[IO](
        Status.NotModified,
        headers = Headers(cacheHeaders.map { case (key, value) => Header.Raw(CIString(key), value) })
      )
    } else {
      jsonResponse(Status.Ok, payload, cacheHeaders*)
    }
  }

  def jsonBody(request: Request[IO]): IO[JsonObject] =
    request
      .as[String]
      .map(raw => parse(raw).toOption.flatMap(_.asObject).getOrElse(JsonObject.empty))
      .handleError(_ => JsonObject.empty)

  def parseUuid(value: String): UUID =
    Try(UUID.fromString(value.toLowerCase)).getOrElse(throw NotFoundProblem())

  def query(request: Request[IO]): Map[String, Seq[String]] =
    request.uri.query.multiParams.view.mapValues(_.toSeq).toMap

  def header(request: Request[IO], name: String): Option[String] =
    request.headers.get(CIString(name)).map(_.head.value)

  def single(query: Map[String, Seq[String]], name: String): Option[String] =
    query.get(name).flatMap {
      case Nil        => None
      case one :: Nil => Some(one)
      case _          => throw BadRequestProblem(s"$name must not be repeated")
    }

  def first(query: Map[String, Seq[String]], name: String): Option[String] =
    query.get(name).flatMap(_.headOption)

  def multi(query: Map[String, Seq[String]], name: String): Seq[String] =
    query.getOrElse(name, Seq.empty)

  def paging(query: Map[String, Seq[String]]): (Int, Int) = {
    val page = intParam(single(query, "page"), 0, "page")
    val size = intParam(single(query, "size"), 20, "size")
    if (page < 0) throw BadRequestProblem("page must not be negative")
    if (size < 1 || size > 100) throw BadRequestProblem("size must be between 1 and 100")
    (page, size)
  }

  def maxLength(value: Option[String], max: Int, name: String): Unit =
    if (value.exists(_.length > max)) throw BadRequestProblem(s"$name must be at most $max characters")

  def escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

  private def intParam(value: Option[String], fallback: Int, name: String): Int =
    value
      .filter(_.nonEmpty)
      .map(raw => Try(raw.toInt).getOrElse(throw BadRequestProblem(s"$name must be an integer")))
      .getOrElse(fallback)
}

object CursorCodec {
  def encode(cursor: BookmarkCursor): String = {
    val payload = Json
      .obj("createdAt" -> Json.fromString(cursor.createdAt.toString), "id" -> Json.fromString(cursor.id.toString))
      .noSpaces
    Base64.getUrlEncoder.withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
  }

  def decode(raw: String): BookmarkCursor =
    try {
      val json = parse(String(Base64.getUrlDecoder.decode(raw), StandardCharsets.UTF_8)).toOption.get
      val cursor = json.hcursor
      BookmarkCursor(
        Instant.parse(cursor.get[String]("createdAt").toOption.get),
        UUID.fromString(cursor.get[String]("id").toOption.get)
      )
    } catch {
      case _: Exception => throw BadRequestProblem("cursor is malformed")
    }
}

object Responses {
  import Wire.obj

  def bookmark(row: BookmarkRow): Json = obj(
    "id" -> Some(Json.fromString(row.id.toString)),
    "url" -> Some(Json.fromString(row.url)),
    "title" -> Some(Json.fromString(row.title)),
    "notes" -> row.notes.map(Json.fromString),
    "tags" -> Some(Json.arr(row.tags.map(Json.fromString)*)),
    "visibility" -> Some(Json.fromString(row.visibility)),
    "status" -> Some(Json.fromString(row.status)),
    "owner" -> Some(Json.fromString(row.owner)),
    "createdAt" -> Some(Json.fromString(row.createdAt.toString)),
    "updatedAt" -> Some(Json.fromString(row.updatedAt.toString))
  )

  def message(row: MessageRow): Json = obj(
    "id" -> Some(Json.fromString(row.id.toString)),
    "key" -> Some(Json.fromString(row.key)),
    "language" -> Some(Json.fromString(row.language)),
    "text" -> Some(Json.fromString(row.text)),
    "description" -> row.description.map(Json.fromString),
    "createdAt" -> Some(Json.fromString(row.createdAt.toString)),
    "updatedAt" -> Some(Json.fromString(row.updatedAt.toString))
  )

  def report(row: ReportRow): Json = obj(
    "id" -> Some(Json.fromString(row.id.toString)),
    "bookmarkId" -> Some(Json.fromString(row.bookmarkId.toString)),
    "reporter" -> Some(Json.fromString(row.reporter)),
    "reason" -> Some(Json.fromString(row.reason)),
    "comment" -> row.comment.map(Json.fromString),
    "status" -> Some(Json.fromString(row.status)),
    "createdAt" -> Some(Json.fromString(row.createdAt.toString)),
    "resolvedBy" -> row.resolvedBy.map(Json.fromString),
    "resolvedAt" -> row.resolvedAt.map(value => Json.fromString(value.toString)),
    "resolutionNote" -> row.resolutionNote.map(Json.fromString)
  )

  def user(row: UserAccountRow): Json = obj(
    "username" -> Some(Json.fromString(row.username)),
    "firstSeen" -> Some(Json.fromString(row.firstSeen.toString)),
    "lastSeen" -> Some(Json.fromString(row.lastSeen.toString)),
    "status" -> Some(Json.fromString(row.status)),
    "blockedReason" -> row.blockedReason.map(Json.fromString),
    "bookmarkCount" -> Some(Json.fromLong(row.bookmarkCount))
  )

  def audit(row: AuditRow): Json = obj(
    "id" -> Some(Json.fromString(row.id.toString)),
    "actor" -> Some(Json.fromString(row.actor)),
    "action" -> Some(Json.fromString(row.action)),
    "targetType" -> Some(Json.fromString(row.targetType)),
    "targetId" -> Some(Json.fromString(row.targetId)),
    "detail" -> row.detail,
    "createdAt" -> Some(Json.fromString(row.createdAt.toString))
  )
}
