package support

import models._
import play.api.libs.json._

object Responses {
  import Wire._

  def bookmark(row: BookmarkRow): JsObject = obj(
    "id" -> Some(JsString(row.id.toString)),
    "url" -> Some(JsString(row.url)),
    "title" -> Some(JsString(row.title)),
    "notes" -> row.notes.map(JsString.apply),
    "tags" -> Some(JsArray(row.tags.map(JsString.apply))),
    "visibility" -> Some(JsString(row.visibility)),
    "status" -> Some(JsString(row.status)),
    "owner" -> Some(JsString(row.owner)),
    "createdAt" -> Some(instant(row.createdAt)),
    "updatedAt" -> Some(instant(row.updatedAt))
  )

  def message(row: MessageRow): JsObject = obj(
    "id" -> Some(JsString(row.id.toString)),
    "key" -> Some(JsString(row.key)),
    "language" -> Some(JsString(row.language)),
    "text" -> Some(JsString(row.text)),
    "description" -> row.description.map(JsString.apply),
    "createdAt" -> Some(instant(row.createdAt)),
    "updatedAt" -> Some(instant(row.updatedAt))
  )

  def report(row: ReportRow): JsObject = obj(
    "id" -> Some(JsString(row.id.toString)),
    "bookmarkId" -> Some(JsString(row.bookmarkId.toString)),
    "reporter" -> Some(JsString(row.reporter)),
    "reason" -> Some(JsString(row.reason)),
    "comment" -> row.comment.map(JsString.apply),
    "status" -> Some(JsString(row.status)),
    "createdAt" -> Some(instant(row.createdAt)),
    "resolvedBy" -> row.resolvedBy.map(JsString.apply),
    "resolvedAt" -> row.resolvedAt.map(instant),
    "resolutionNote" -> row.resolutionNote.map(JsString.apply)
  )

  def user(row: UserAccountRow): JsObject = obj(
    "username" -> Some(JsString(row.username)),
    "firstSeen" -> Some(instant(row.firstSeen)),
    "lastSeen" -> Some(instant(row.lastSeen)),
    "status" -> Some(JsString(row.status)),
    "blockedReason" -> row.blockedReason.map(JsString.apply),
    "bookmarkCount" -> Some(JsNumber(row.bookmarkCount))
  )

  def audit(row: AuditRow): JsObject = obj(
    "id" -> Some(JsString(row.id.toString)),
    "actor" -> Some(JsString(row.actor)),
    "action" -> Some(JsString(row.action)),
    "targetType" -> Some(JsString(row.targetType)),
    "targetId" -> Some(JsString(row.targetId)),
    "detail" -> row.detail,
    "createdAt" -> Some(instant(row.createdAt))
  )

  given OWrites[BookmarkRow] = OWrites(bookmark)
  given OWrites[MessageRow] = OWrites(message)
  given OWrites[ReportRow] = OWrites(report)
  given OWrites[UserAccountRow] = OWrites(user)
  given OWrites[AuditRow] = OWrites(audit)
}
