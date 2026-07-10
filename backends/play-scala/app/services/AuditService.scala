package services

import play.api.libs.json.{JsObject, Json}
import repositories.Db

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Singleton}

@Singleton
class AuditService @Inject() (db: Db) {
  def record(
      conn: Connection,
      actor: String,
      action: String,
      targetType: String,
      targetId: String,
      detail: JsObject = Json.obj()
  ): Unit =
    db.execute(
      conn,
      "insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at) values (?, ?, ?, ?, ?, ?, ?)",
      Seq(
        UUID.randomUUID(),
        actor,
        action,
        targetType,
        targetId,
        if (detail.fields.isEmpty) None else Some(detail),
        Instant.now()
      )
    )
}
