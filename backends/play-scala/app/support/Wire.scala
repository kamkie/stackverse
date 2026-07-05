package support

import models.{BadRequestProblem, NotFoundProblem}
import play.api.libs.json._
import play.api.mvc._

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.{Base64, UUID}
import scala.util.Try

object Wire {
  val Iso: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
  val JsonContentType = "application/json; charset=utf-8"
  val ProblemContentType = "application/problem+json"

  def instant(value: Instant): JsString = JsString(Iso.format(value))

  def obj(fields: (String, Option[JsValue])*): JsObject =
    JsObject(fields.collect { case (key, Some(value)) => key -> value })

  def problem(status: Int, title: String, detail: Option[String] = None, errors: Option[Seq[JsObject]] = None): Result = {
    Results.Status(status)(
      obj(
        "type" -> Some(JsString("about:blank")),
        "title" -> Some(JsString(title)),
        "status" -> Some(JsNumber(status)),
        "detail" -> detail.map(JsString),
        "errors" -> errors.map(values => JsArray(values))
      )
    ).as(ProblemContentType)
  }

  def withEtag(request: RequestHeader, payload: JsValue, headers: (String, String)*): Result = {
    val body = Json.stringify(payload)
    val digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))
    val etag = "\"" + Base64.getUrlEncoder.withoutPadding().encodeToString(digest) + "\""
    val cacheHeaders = Seq("ETag" -> etag, "Cache-Control" -> "no-cache") ++ headers
    val matches = request.headers.get("If-None-Match").exists(_.split(",").exists(_.trim == etag))
    if (matches) {
      Result(
        header = ResponseHeader(304, cacheHeaders.toMap),
        body = play.api.http.HttpEntity.NoEntity
      )
    } else {
      Results.Ok(body).as(JsonContentType).withHeaders(cacheHeaders: _*)
    }
  }

  def parseUuid(value: String): UUID =
    Try(UUID.fromString(value.toLowerCase)).getOrElse(throw new NotFoundProblem)

  def single(query: Map[String, Seq[String]], name: String): Option[String] =
    query.get(name).flatMap {
      case Nil => None
      case one :: Nil => Some(one)
      case _ => throw new BadRequestProblem(s"$name must not be repeated")
    }

  def first(query: Map[String, Seq[String]], name: String): Option[String] =
    query.get(name).flatMap(_.headOption)

  def multi(query: Map[String, Seq[String]], name: String): Seq[String] =
    query.getOrElse(name, Seq.empty)

  def paging(query: Map[String, Seq[String]]): (Int, Int) = {
    val page = intParam(single(query, "page"), 0, "page")
    val size = intParam(single(query, "size"), 20, "size")
    if (page < 0) throw new BadRequestProblem("page must not be negative")
    if (size < 1 || size > 100) throw new BadRequestProblem("size must be between 1 and 100")
    (page, size)
  }

  def maxLength(value: Option[String], max: Int, name: String): Unit =
    if (value.exists(_.length > max)) throw new BadRequestProblem(s"$name must be at most $max characters")

  def escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

  private def intParam(value: Option[String], fallback: Int, name: String): Int =
    value.filter(_.nonEmpty).map(raw => Try(raw.toInt).getOrElse(throw new BadRequestProblem(s"$name must be an integer"))).getOrElse(fallback)
}
