package support

import models.*
import play.api.libs.json.*
import play.api.mvc.{AnyContent, Request}

import scala.util.Try

case class BookmarkInput(
    url: String,
    title: String,
    notes: Option[String],
    tags: Seq[String],
    visibility: String
)
case class MessageInput(key: String, language: String, text: String, description: Option[String])
case class ReportInput(reason: String, comment: Option[String])
case class ResolutionInput(resolution: String, note: Option[String])
case class BookmarkStatusInput(status: String, note: Option[String])
case class UserStatusInput(status: String, reason: Option[String])

object InputJson {
  private val TagPattern = "^[a-z0-9-]{1,30}$".r
  private val KeyPattern = "^[a-z0-9-]+(\\.[a-z0-9-]+)*$".r
  private val LanguagePattern = "^[a-z]{2}$".r
  private val BadRequestError = "stackverse.bad-request"

  given Reads[BookmarkInput] = Reads { json =>
    val body = objectBody(json)
    val validator = new Validator()
    val url = (body \ "url").asOpt[String].map(_.trim).getOrElse("")
    if (url.isEmpty) validator.reject("url", "validation.url.required")
    else
      validator.check(
        url.length <= 2000 && isHttpUrl(url),
        "url",
        "validation.url.invalid"
      )
    val title = (body \ "title").asOpt[String].map(_.trim).getOrElse("")
    validator.check(title.nonEmpty, "title", "validation.title.required")
    validator.check(title.length <= 200, "title", "validation.title.too-long")
    val notes = (body \ "notes").asOpt[String]
    validator.check(
      notes.forall(_.length <= 4000),
      "notes",
      "validation.notes.too-long"
    )
    val rawTags = (body \ "tags").asOpt[Seq[JsValue]].getOrElse(Seq.empty)
    val tags = rawTags
      .map {
        case JsString(value) => value
        case other           => Json.stringify(other)
      }
      .map(_.trim.toLowerCase)
      .distinct
    validator.check(tags.length <= 10, "tags", "validation.tags.too-many")
    validator.check(
      tags.forall(tag => TagPattern.matches(tag)),
      "tags",
      "validation.tag.invalid"
    )
    val visibility = (body \ "visibility").asOpt[String].getOrElse("private")
    if (visibility != "private" && visibility != "public")
      badRequest("visibility", s"unknown visibility: $visibility")
    else validationResult(validator)(BookmarkInput(url, title, notes, tags, visibility))
  }

  given Reads[MessageInput] = Reads { json =>
    val body = objectBody(json)
    val validator = new Validator()
    val key = (body \ "key").asOpt[String].map(_.trim).getOrElse("")
    validator.check(
      KeyPattern.matches(key) && key.length <= 150,
      "key",
      "validation.message.key.invalid"
    )
    val language = (body \ "language").asOpt[String].map(_.trim).getOrElse("")
    validator.check(
      LanguagePattern.matches(language),
      "language",
      "validation.message.language.invalid"
    )
    val text = (body \ "text").asOpt[String].getOrElse("")
    validator.check(text.nonEmpty, "text", "validation.message.text.required")
    validator.check(
      text.length <= 2000,
      "text",
      "validation.message.text.too-long"
    )
    val description = (body \ "description").asOpt[String]
    validator.check(
      description.forall(_.length <= 1000),
      "description",
      "validation.message.description.too-long"
    )
    validationResult(validator)(MessageInput(key, language, text, description))
  }

  given Reads[ReportInput] = Reads { json =>
    val body = objectBody(json)
    val validator = new Validator()
    val reason = (body \ "reason").asOpt[String]
    validator.check(
      reason.exists(Seq("spam", "offensive", "broken-link", "other").contains),
      "reason",
      "validation.report.reason.invalid"
    )
    val comment = (body \ "comment").asOpt[String]
    validator.check(
      comment.forall(_.length <= 1000),
      "comment",
      "validation.report.comment.too-long"
    )
    validationResult(validator)(ReportInput(reason.get, comment))
  }

  given Reads[ResolutionInput] = Reads { json =>
    val body = objectBody(json)
    val validator = new Validator()
    val resolution = (body \ "resolution").asOpt[String]
    validator.check(
      resolution.exists(Seq("open", "dismissed", "actioned").contains),
      "resolution",
      "validation.resolution.invalid"
    )
    val note = (body \ "note").asOpt[String]
    validator.check(
      note.forall(_.length <= 1000),
      "note",
      "validation.resolution.note.too-long"
    )
    validationResult(validator)(ResolutionInput(resolution.get, note))
  }

  given Reads[BookmarkStatusInput] = Reads { json =>
    val body = objectBody(json)
    val validator = new Validator()
    val status = (body \ "status").asOpt[String]
    validator.check(
      status.exists(value => value == "active" || value == "hidden"),
      "status",
      "validation.bookmark-status.invalid"
    )
    val note = (body \ "note").asOpt[String]
    validator.check(
      note.forall(_.length <= 1000),
      "note",
      "validation.bookmark-status.note.too-long"
    )
    validationResult(validator)(BookmarkStatusInput(status.get, note))
  }

  given Reads[UserStatusInput] = Reads { json =>
    val body = objectBody(json)
    val status = (body \ "status").asOpt[String].filter(value => value == "active" || value == "blocked")
    status match {
      case None        => badRequest("status", "status is required")
      case Some(value) =>
        val reason = (body \ "reason").asOpt[String].map(_.trim)
        val validator = new Validator()
        if (value == "blocked") {
          validator.check(
            reason.exists(_.nonEmpty),
            "reason",
            "validation.block.reason.required"
          )
          validator.check(
            reason.forall(_.length <= 1000),
            "reason",
            "validation.block.reason.too-long"
          )
        }
        validationResult(validator)(UserStatusInput(value, reason))
    }
  }

  def read[A: Reads](request: Request[AnyContent]): A = {
    val json = request.body.asJson.getOrElse(Json.obj())
    json.validate[A] match {
      case JsSuccess(value, _) => value
      case JsError(errors)     =>
        errors.iterator
          .flatMap { case (_, validationErrors) => validationErrors.iterator }
          .find(_.message == BadRequestError)
          .foreach { error =>
            throw new BadRequestProblem(error.args.headOption.map(_.toString).getOrElse("Bad request"))
          }
        val fieldViolations = errors.flatMap { case (path, validationErrors) =>
          validationErrors.map { error =>
            FieldViolation(field(path), error.message)
          }
        }
        throw new ValidationProblem(fieldViolations.toList)
    }
  }

  private def validationResult[A](validator: Validator)(value: => A): JsResult[A] =
    validator.result(value) match {
      case Right(result)    => JsSuccess(result)
      case Left(violations) =>
        JsError(violations.map { violation =>
          (JsPath \ violation.field) -> Seq(JsonValidationError(violation.messageKey))
        })
    }

  private def badRequest(field: String, detail: String): JsResult[Nothing] =
    JsError(JsPath \ field, JsonValidationError(BadRequestError, detail))

  private def field(path: JsPath): String =
    path.path.lastOption.collect { case KeyPathNode(key) => key }.getOrElse("body")

  private def objectBody(json: JsValue): JsObject = json match {
    case value: JsObject => value
    case _               => Json.obj()
  }

  private def isHttpUrl(value: String): Boolean =
    Try(new java.net.URI(value)).toOption.exists(uri =>
      (uri.getScheme == "http" || uri.getScheme == "https") && Option(uri.getHost).exists(
        _.nonEmpty
      )
    )
}
