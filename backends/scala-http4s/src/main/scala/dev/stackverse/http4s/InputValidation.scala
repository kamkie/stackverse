package dev.stackverse.http4s

import io.circe.JsonObject

import java.net.URI
import scala.util.Try

object InputValidation {
  private val TagPattern = "^[a-z0-9-]{1,30}$".r
  private val KeyPattern = "^[a-z0-9-]+(\\.[a-z0-9-]+)*$".r
  private val LanguagePattern = "^[a-z]{2}$".r

  case class BookmarkInput(url: String, title: String, notes: Option[String], tags: Seq[String], visibility: String)
  case class MessageInput(key: String, language: String, text: String, description: Option[String])
  case class ReportInput(reason: String, comment: Option[String])
  case class ListFilters(tags: Seq[String], q: Option[String], visibility: Option[String])

  def validateBookmarkInput(body: JsonObject): BookmarkInput = {
    val validator = Validator()
    val url = body("url").flatMap(_.asString).map(_.trim).getOrElse("")
    if (url.isEmpty) validator.reject("url", "validation.url.required")
    else validator.check(url.length <= 2000 && isHttpUrl(url), "url", "validation.url.invalid")
    val title = body("title").flatMap(_.asString).map(_.trim).getOrElse("")
    validator.check(title.nonEmpty, "title", "validation.title.required")
    validator.check(title.length <= 200, "title", "validation.title.too-long")
    val notes = body("notes").flatMap(_.asString)
    validator.check(notes.forall(_.length <= 4000), "notes", "validation.notes.too-long")
    val rawTags = body("tags").flatMap(_.asArray).getOrElse(Vector.empty)
    val tags = rawTags.map(value => value.asString.getOrElse(value.noSpaces)).map(_.trim.toLowerCase).distinct
    validator.check(tags.length <= 10, "tags", "validation.tags.too-many")
    validator.check(tags.forall(tag => TagPattern.matches(tag)), "tags", "validation.tag.invalid")
    val visibility = body("visibility").flatMap(_.asString).getOrElse("private")
    if (visibility != "private" && visibility != "public") throw BadRequestProblem(s"unknown visibility: $visibility")
    validator.throwIfInvalid()
    BookmarkInput(url, title, notes, tags, visibility)
  }

  def validateMessageInput(body: JsonObject): MessageInput = {
    val validator = Validator()
    val key = body("key").flatMap(_.asString).map(_.trim).getOrElse("")
    validator.check(KeyPattern.matches(key) && key.length <= 150, "key", "validation.message.key.invalid")
    val language = body("language").flatMap(_.asString).map(_.trim).getOrElse("")
    validator.check(LanguagePattern.matches(language), "language", "validation.message.language.invalid")
    val text = body("text").flatMap(_.asString).getOrElse("")
    validator.check(text.nonEmpty, "text", "validation.message.text.required")
    validator.check(text.length <= 2000, "text", "validation.message.text.too-long")
    val description = body("description").flatMap(_.asString)
    validator.check(description.forall(_.length <= 1000), "description", "validation.message.description.too-long")
    validator.throwIfInvalid()
    MessageInput(key, language, text, description)
  }

  def validateReportInput(body: JsonObject): ReportInput = {
    val validator = Validator()
    val reason = body("reason").flatMap(_.asString)
    validator.check(
      reason.exists(Seq("spam", "offensive", "broken-link", "other").contains),
      "reason",
      "validation.report.reason.invalid"
    )
    val comment = body("comment").flatMap(_.asString)
    validator.check(comment.forall(_.length <= 1000), "comment", "validation.report.comment.too-long")
    validator.throwIfInvalid()
    ReportInput(reason.get, comment)
  }

  private def isHttpUrl(value: String): Boolean =
    Try(URI(value)).toOption.exists(uri =>
      (uri.getScheme == "http" || uri.getScheme == "https") && Option(uri.getHost).exists(_.nonEmpty)
    )
}
