package models

import play.api.libs.json.JsObject

import java.time.Instant
import java.util.UUID
import scala.collection.mutable.ArrayBuffer

class ApiProblem(
    val status: Int,
    val title: String,
    val detail: Option[String] = None,
    val detailKey: Option[String] = None
) extends RuntimeException(detail.getOrElse(title))

class NotFoundProblem extends ApiProblem(404, "Not Found")
class UnauthorizedProblem(detail: String = "Authentication is required.")
    extends ApiProblem(401, "Unauthorized", Some(detail))
class ForbiddenProblem(detail: String, key: Option[String] = None)
    extends ApiProblem(403, "Forbidden", Some(detail), key)
class ConflictProblem(detail: String, key: Option[String] = None) extends ApiProblem(409, "Conflict", Some(detail), key)
class BadRequestProblem(detail: String) extends ApiProblem(400, "Bad Request", Some(detail))

case class FieldViolation(field: String, messageKey: String)
class ValidationProblem(val violations: Seq[FieldViolation]) extends RuntimeException("Validation failed")

class Validator {
  private val violations = ArrayBuffer.empty[FieldViolation]
  def reject(field: String, key: String): Unit = violations += FieldViolation(field, key)
  def check(condition: Boolean, field: String, key: String): Unit = if (!condition) reject(field, key)
  def throwIfInvalid(): Unit = if (violations.nonEmpty) throw new ValidationProblem(violations.toSeq)
}

case class Caller(username: String, roles: Seq[String], name: Option[String], email: Option[String])
case class BookmarkCursor(createdAt: Instant, id: UUID)

case class BookmarkRow(
    id: UUID,
    owner: String,
    url: String,
    title: String,
    notes: Option[String],
    tags: Seq[String],
    visibility: String,
    status: String,
    createdAt: Instant,
    updatedAt: Instant
)

case class MessageRow(
    id: UUID,
    key: String,
    language: String,
    text: String,
    description: Option[String],
    createdAt: Instant,
    updatedAt: Instant
)

case class ReportRow(
    id: UUID,
    bookmarkId: UUID,
    reporter: String,
    reason: String,
    comment: Option[String],
    status: String,
    resolvedBy: Option[String],
    resolvedAt: Option[Instant],
    resolutionNote: Option[String],
    createdAt: Instant
)

case class UserAccountRow(
    username: String,
    firstSeen: Instant,
    lastSeen: Instant,
    status: String,
    blockedReason: Option[String],
    bookmarkCount: Long
)

case class AuditRow(
    id: UUID,
    actor: String,
    action: String,
    targetType: String,
    targetId: String,
    detail: Option[JsObject],
    createdAt: Instant
)
