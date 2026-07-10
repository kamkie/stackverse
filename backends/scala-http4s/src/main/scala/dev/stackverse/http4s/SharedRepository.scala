package dev.stackverse.http4s

import io.circe.{Json, JsonObject}

import java.sql.Connection
import java.time.Instant
import java.util.UUID

final class SharedRepository(db: Db) {
  val withBookmarkCountQuery =
    """select u.*, (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count
      |from user_accounts u""".stripMargin

  def count(conn: Connection, sql: String, params: Seq[Any] = Seq.empty): Long =
    db.one(conn, sql, params)(_.getLong("count")).getOrElse(0L)

  def findBookmark(conn: Connection, id: UUID): Option[BookmarkRow] =
    db.one(conn, "select * from bookmarks where id = ?", Seq(id))(Rows.bookmark)

  def findUser(conn: Connection, username: String): Option[UserAccountRow] =
    db.one(conn, s"$withBookmarkCountQuery where u.username = ?", Seq(username))(Rows.user)

  def recordAudit(
      conn: Connection,
      actor: String,
      action: String,
      targetType: String,
      targetId: String,
      detail: JsonObject = JsonObject.empty
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
        if (detail.isEmpty) None else Some(detail),
        Instant.now()
      )
    )

  def countPerDay(conn: Connection, table: String, column: String, from: Instant): Map[String, Long] =
    db.query(
      conn,
      s"select ($column at time zone 'UTC')::date::text as day, count(*) as count from $table where $column >= ? group by day",
      Seq(from)
    ) { rs =>
      rs.getString("day") -> rs.getLong("count")
    }.toMap
}
