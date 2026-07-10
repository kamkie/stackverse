package repositories

import config.BackendConfig
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import models._
import org.flywaydb.core.Flyway
import org.postgresql.util.PGobject
import play.api.libs.json._
import services.EventLogger

import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp}
import java.time.{Instant, LocalDate}
import java.util.UUID
import javax.inject._
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.util.Using

@Singleton
class Db @Inject() (config: BackendConfig, logger: EventLogger) {
  val dataSource: HikariDataSource = {
    val hikari = new HikariConfig()
    hikari.setJdbcUrl(s"jdbc:postgresql://${config.dbHost}:${config.dbPort}/${config.dbName}")
    hikari.setUsername(config.dbUser)
    hikari.setPassword(config.dbPassword)
    // Keep in sync with conf/application.conf database-dispatcher fixed-pool-size.
    hikari.setMaximumPoolSize(10)
    hikari.setMinimumIdle(0)
    // Startup migrations still fail the application eagerly; delaying the first pool connection
    // keeps Guice application tests and liveness wiring independent from a running PostgreSQL.
    hikari.setInitializationFailTimeout(-1)
    hikari.setPoolName("stackverse-play-scala")
    new HikariDataSource(hikari)
  }

  def migrate(): Unit = {
    val result = Flyway
      .configure()
      .dataSource(dataSource)
      .locations("classpath:db/migration")
      .load()
      .migrate()
    result.migrations.asScala.foreach { migration =>
      logger.event(
        "info",
        "db_migration_applied",
        "success",
        s"Applied migration ${migration.filepath}",
        "migration" -> JsString(migration.filepath)
      )
    }
  }

  def close(): Unit = dataSource.close()

  def withConnection[T](fn: Connection => T): T = {
    val conn = dataSource.getConnection
    try fn(conn)
    finally conn.close()
  }

  def transaction[T](fn: Connection => T): T = withConnection { conn =>
    val oldAutoCommit = conn.getAutoCommit
    conn.setAutoCommit(false)
    try {
      val value = fn(conn)
      conn.commit()
      value
    } catch {
      case error: Throwable =>
        scala.util.Try(conn.rollback())
        throw error
    } finally conn.setAutoCommit(oldAutoCommit)
  }

  def query[T](conn: Connection, sql: String, params: Seq[Any] = Seq.empty)(map: ResultSet => T): Seq[T] =
    Using.resource(prepare(conn, sql, params)) { ps =>
      Using.resource(ps.executeQuery()) { rs =>
        val rows = ArrayBuffer.empty[T]
        while (rs.next()) rows += map(rs)
        rows.toSeq
      }
    }

  def one[T](conn: Connection, sql: String, params: Seq[Any] = Seq.empty)(map: ResultSet => T): Option[T] =
    query(conn, sql, params)(map).headOption

  def execute(conn: Connection, sql: String, params: Seq[Any] = Seq.empty): Int =
    Using.resource(prepare(conn, sql, params))(_.executeUpdate())

  def returning[T](conn: Connection, sql: String, params: Seq[Any] = Seq.empty)(map: ResultSet => T): T =
    one(conn, sql, params)(map).getOrElse(throw new IllegalStateException("statement returned no rows"))

  def count(conn: Connection, sql: String, params: Seq[Any] = Seq.empty): Long =
    one(conn, sql, params)(_.getLong("count")).getOrElse(0L)

  private def prepare(conn: Connection, sql: String, params: Seq[Any]): PreparedStatement = {
    val ps = conn.prepareStatement(sql)
    params.zipWithIndex.foreach { case (value, index) => setParam(conn, ps, index + 1, value) }
    ps
  }

  private def setParam(conn: Connection, ps: PreparedStatement, index: Int, value: Any): Unit = value match {
    case null             => ps.setObject(index, null)
    case None             => ps.setObject(index, null)
    case Some(v)          => setParam(conn, ps, index, v)
    case value: String    => ps.setString(index, value)
    case value: Int       => ps.setInt(index, value)
    case value: Long      => ps.setLong(index, value)
    case value: Boolean   => ps.setBoolean(index, value)
    case value: UUID      => ps.setObject(index, value)
    case value: Instant   => ps.setTimestamp(index, Timestamp.from(value))
    case value: LocalDate => ps.setObject(index, value)
    case value: JsObject  =>
      val pg = new PGobject()
      pg.setType("jsonb")
      pg.setValue(Json.stringify(value))
      ps.setObject(index, pg)
    case values: Seq[_] =>
      ps.setArray(index, conn.createArrayOf("text", values.map(_.toString).toArray[AnyRef]))
    case other => ps.setObject(index, other)
  }
}

object Rows {
  private def optString(rs: ResultSet, name: String): Option[String] = Option(rs.getString(name))
  private def optInstant(rs: ResultSet, name: String): Option[Instant] = Option(rs.getTimestamp(name)).map(_.toInstant)
  private def instant(rs: ResultSet, name: String): Instant = rs.getTimestamp(name).toInstant

  def bookmark(rs: ResultSet): BookmarkRow = {
    val array = Option(rs.getArray("tags")).map(_.getArray.asInstanceOf[Array[String]].toSeq).getOrElse(Seq.empty)
    BookmarkRow(
      id = rs.getObject("id", classOf[UUID]),
      owner = rs.getString("owner"),
      url = rs.getString("url"),
      title = rs.getString("title"),
      notes = optString(rs, "notes"),
      tags = array,
      visibility = rs.getString("visibility"),
      status = rs.getString("status"),
      createdAt = instant(rs, "created_at"),
      updatedAt = instant(rs, "updated_at")
    )
  }

  def message(rs: ResultSet): MessageRow = MessageRow(
    id = rs.getObject("id", classOf[UUID]),
    key = rs.getString("key"),
    language = rs.getString("language"),
    text = rs.getString("text"),
    description = optString(rs, "description"),
    createdAt = instant(rs, "created_at"),
    updatedAt = instant(rs, "updated_at")
  )

  def report(rs: ResultSet): ReportRow = ReportRow(
    id = rs.getObject("id", classOf[UUID]),
    bookmarkId = rs.getObject("bookmark_id", classOf[UUID]),
    reporter = rs.getString("reporter"),
    reason = rs.getString("reason"),
    comment = optString(rs, "comment"),
    status = rs.getString("status"),
    resolvedBy = optString(rs, "resolved_by"),
    resolvedAt = optInstant(rs, "resolved_at"),
    resolutionNote = optString(rs, "resolution_note"),
    createdAt = instant(rs, "created_at")
  )

  def user(rs: ResultSet): UserAccountRow = UserAccountRow(
    username = rs.getString("username"),
    firstSeen = instant(rs, "first_seen"),
    lastSeen = instant(rs, "last_seen"),
    status = rs.getString("status"),
    blockedReason = optString(rs, "blocked_reason"),
    bookmarkCount = rs.getLong("bookmark_count")
  )

  def audit(rs: ResultSet): AuditRow = {
    val detail = Option(rs.getObject("detail")).map(value => Json.parse(value.toString).as[JsObject])
    AuditRow(
      id = rs.getObject("id", classOf[UUID]),
      actor = rs.getString("actor"),
      action = rs.getString("action"),
      targetType = rs.getString("target_type"),
      targetId = rs.getString("target_id"),
      detail = detail,
      createdAt = instant(rs, "created_at")
    )
  }
}
