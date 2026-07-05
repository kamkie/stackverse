package stackverse

import com.google.inject.AbstractModule
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.RemoteJWKSet
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.{Logger => OtelLogger, Severity}
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import org.flywaydb.core.Flyway
import org.postgresql.util.PGobject
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.mvc._

import java.net.URL
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.sql.{Connection, PreparedStatement, ResultSet, SQLException, Timestamp}
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.{Base64, UUID}
import javax.inject._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try, Using}

class Module extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[StackverseBackend]).asEagerSingleton()
  }
}

case class BackendConfig(
    port: Int,
    dbHost: String,
    dbPort: Int,
    dbName: String,
    dbUser: String,
    dbPassword: String,
    oidcIssuerUri: String,
    oidcJwksUri: Option[String],
    seedMessagesDir: Path,
    logLevel: String,
    logFormat: String,
    otelEnabled: Boolean
)

object BackendConfig {
  private def env(name: String, fallback: String): String =
    Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty).getOrElse(fallback)

  private def intEnv(name: String, fallback: Int): Int =
    Try(env(name, fallback.toString).toInt).getOrElse {
      throw new IllegalArgumentException(s"$name must be an integer")
    }

  private def seedDir: Path = {
    Option(System.getenv("SEED_MESSAGES_DIR")).map(Paths.get(_)).getOrElse {
      val candidates = Seq(
        Paths.get("../../spec/messages"),
        Paths.get("spec/messages"),
        Paths.get("/app/spec/messages")
      )
      candidates.find(Files.isDirectory(_)).getOrElse(candidates.head)
    }
  }

  def load(): BackendConfig = BackendConfig(
    port = intEnv("PORT", 8080),
    dbHost = env("DB_HOST", "localhost"),
    dbPort = intEnv("DB_PORT", 5432),
    dbName = env("DB_NAME", "stackverse"),
    dbUser = env("DB_USER", "stackverse"),
    dbPassword = env("DB_PASSWORD", "stackverse"),
    oidcIssuerUri = env("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse"),
    oidcJwksUri = Option(System.getenv("OIDC_JWKS_URI")).map(_.trim).filter(_.nonEmpty),
    seedMessagesDir = seedDir,
    logLevel = env("LOG_LEVEL", "info").toLowerCase,
    logFormat = env("LOG_FORMAT", "json").toLowerCase,
    otelEnabled = env("OTEL_SDK_DISABLED", "true").toLowerCase == "false"
  )
}

class EventLogger(config: BackendConfig) {
  private val priorities = Map("debug" -> 10, "info" -> 20, "warn" -> 30, "error" -> 40, "fatal" -> 50)
  private val threshold = priorities.getOrElse(config.logLevel, 20)
  private val otelSdk: Option[OpenTelemetrySdk] =
    if (config.otelEnabled) Some(AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk) else None
  private val otelLogger: Option[OtelLogger] =
    otelSdk.map(_.getLogsBridge.loggerBuilder("stackverse-backend-play-scala").build())

  def event(level: String, event: String, outcome: String, message: String, fields: (String, JsValue)*): Unit =
    write(level, message, Some(event), Some(outcome), fields: _*)

  def line(level: String, message: String, fields: (String, JsValue)*): Unit =
    write(level, message, None, None, fields: _*)

  private def write(
      level: String,
      message: String,
      eventName: Option[String],
      outcome: Option[String],
      fields: (String, JsValue)*
  ): Unit = {
    if (priorities.getOrElse(level, 20) < threshold) return
    val base = Seq(
      "timestamp" -> JsString(DateTimeFormatter.ISO_INSTANT.format(Instant.now())),
      "level" -> JsString(level),
      "message" -> JsString(message)
    ) ++ eventName.map("event" -> JsString(_)) ++ outcome.map("outcome" -> JsString(_))
    val obj = JsObject((base ++ fields).filterNot(_._2 == JsNull))
    if (config.logFormat == "text") {
      val suffix = fields.collect { case (key, value) if value != JsNull => s"$key=${Json.stringify(value)}" }
      println((Seq(level.toUpperCase, message) ++ suffix).mkString(" "))
    } else {
      println(Json.stringify(obj))
    }
    exportOtel(level, message, eventName, outcome, fields: _*)
  }

  def shutdown(): Unit = {
    otelSdk.foreach(sdk => Try(sdk.close()))
  }

  private def exportOtel(
      level: String,
      message: String,
      eventName: Option[String],
      outcome: Option[String],
      fields: (String, JsValue)*
  ): Unit = {
    otelLogger.foreach { logger =>
      try {
        val record = logger.logRecordBuilder()
          .setTimestamp(Instant.now())
          .setSeverity(severity(level))
          .setBody(message)
        eventName.foreach(value => record.setAttribute(AttributeKey.stringKey("event"), value))
        outcome.foreach(value => record.setAttribute(AttributeKey.stringKey("outcome"), value))
        fields.foreach {
          case (key, JsString(value)) => record.setAttribute(AttributeKey.stringKey(key), value)
          case (key, JsNumber(value)) => record.setAttribute(AttributeKey.stringKey(key), value.toString)
          case (key, JsBoolean(value)) => record.setAttribute(AttributeKey.stringKey(key), value.toString)
          case (key, JsNull) => ()
          case (key, value) => record.setAttribute(AttributeKey.stringKey(key), Json.stringify(value))
        }
        record.emit()
      } catch {
        case _: Throwable => ()
      }
    }
  }

  private def severity(level: String): Severity = level match {
    case "debug" => Severity.DEBUG
    case "warn" => Severity.WARN
    case "error" => Severity.ERROR
    case "fatal" => Severity.FATAL
    case _ => Severity.INFO
  }
}

class ApiProblem(val status: Int, val title: String, val detail: Option[String] = None, val detailKey: Option[String] = None)
    extends RuntimeException(detail.getOrElse(title))

class NotFoundProblem extends ApiProblem(404, "Not Found")
class UnauthorizedProblem(detail: String = "Authentication is required.") extends ApiProblem(401, "Unauthorized", Some(detail))
class ForbiddenProblem(detail: String, key: Option[String] = None) extends ApiProblem(403, "Forbidden", Some(detail), key)
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

object CursorCodec {
  def encode(cursor: BookmarkCursor): String = {
    val payload = Json.stringify(Json.obj("createdAt" -> cursor.createdAt.toString, "id" -> cursor.id.toString))
    Base64.getUrlEncoder.withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
  }

  def decode(raw: String): BookmarkCursor = {
    try {
      val json = Json.parse(new String(Base64.getUrlDecoder.decode(raw), StandardCharsets.UTF_8))
      val createdAt = Instant.parse((json \ "createdAt").as[String])
      val id = UUID.fromString((json \ "id").as[String])
      BookmarkCursor(createdAt, id)
    } catch {
      case _: Exception => throw new BadRequestProblem("cursor is malformed")
    }
  }
}

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

class Db(config: BackendConfig, logger: EventLogger) {
  val dataSource: HikariDataSource = {
    val hikari = new HikariConfig()
    hikari.setJdbcUrl(s"jdbc:postgresql://${config.dbHost}:${config.dbPort}/${config.dbName}")
    hikari.setUsername(config.dbUser)
    hikari.setPassword(config.dbPassword)
    hikari.setMaximumPoolSize(10)
    hikari.setPoolName("stackverse-play-scala")
    new HikariDataSource(hikari)
  }

  def migrate(): Unit = {
    val result = Flyway.configure()
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
        Try(conn.rollback())
        throw error
    } finally {
      conn.setAutoCommit(oldAutoCommit)
    }
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

  private def prepare(conn: Connection, sql: String, params: Seq[Any]): PreparedStatement = {
    val ps = conn.prepareStatement(sql)
    params.zipWithIndex.foreach { case (value, index) => setParam(conn, ps, index + 1, value) }
    ps
  }

  private def setParam(conn: Connection, ps: PreparedStatement, index: Int, value: Any): Unit = value match {
    case null => ps.setObject(index, null)
    case None => ps.setObject(index, null)
    case Some(v) => setParam(conn, ps, index, v)
    case value: String => ps.setString(index, value)
    case value: Int => ps.setInt(index, value)
    case value: Long => ps.setLong(index, value)
    case value: Boolean => ps.setBoolean(index, value)
    case value: UUID => ps.setObject(index, value)
    case value: Instant => ps.setTimestamp(index, Timestamp.from(value))
    case value: LocalDate => ps.setObject(index, value)
    case value: JsObject =>
      val pg = new PGobject()
      pg.setType("jsonb")
      pg.setValue(Json.stringify(value))
      ps.setObject(index, pg)
    case values: Seq[_] =>
      ps.setArray(index, conn.createArrayOf("text", values.map(_.toString).toArray[AnyRef]))
    case other => ps.setObject(index, other)
  }
}

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

object Responses {
  import Wire._

  def bookmark(row: BookmarkRow): JsObject = obj(
    "id" -> Some(JsString(row.id.toString)),
    "url" -> Some(JsString(row.url)),
    "title" -> Some(JsString(row.title)),
    "notes" -> row.notes.map(JsString),
    "tags" -> Some(JsArray(row.tags.map(JsString))),
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
    "description" -> row.description.map(JsString),
    "createdAt" -> Some(instant(row.createdAt)),
    "updatedAt" -> Some(instant(row.updatedAt))
  )

  def report(row: ReportRow): JsObject = obj(
    "id" -> Some(JsString(row.id.toString)),
    "bookmarkId" -> Some(JsString(row.bookmarkId.toString)),
    "reporter" -> Some(JsString(row.reporter)),
    "reason" -> Some(JsString(row.reason)),
    "comment" -> row.comment.map(JsString),
    "status" -> Some(JsString(row.status)),
    "createdAt" -> Some(instant(row.createdAt)),
    "resolvedBy" -> row.resolvedBy.map(JsString),
    "resolvedAt" -> row.resolvedAt.map(instant),
    "resolutionNote" -> row.resolutionNote.map(JsString)
  )

  def user(row: UserAccountRow): JsObject = obj(
    "username" -> Some(JsString(row.username)),
    "firstSeen" -> Some(instant(row.firstSeen)),
    "lastSeen" -> Some(instant(row.lastSeen)),
    "status" -> Some(JsString(row.status)),
    "blockedReason" -> row.blockedReason.map(JsString),
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
}

class I18n(db: Db) {
  val DefaultLanguage = "en"

  def resolve(queryLang: Option[String], acceptLanguage: Option[String]): String =
    db.withConnection { conn =>
      val supported = db.query(conn, "select distinct language from messages")(rs => rs.getString("language")).toSet
      queryLang.filter(supported.contains).orElse(parseAcceptLanguage(acceptLanguage).find(supported.contains)).getOrElse(DefaultLanguage)
    }

  def localize(key: String, language: String): String =
    db.withConnection { conn =>
      db.one(
        conn,
        "select text from messages where key = ? and language = any(?::text[]) order by case when language = ? then 0 else 1 end limit 1",
        Seq(key, Seq(language, DefaultLanguage).distinct, language)
      )(_.getString("text")).getOrElse(key)
    }

  def bundle(language: String): JsObject =
    db.withConnection { conn =>
      val rows = db.query(
        conn,
        "select key, language, text from messages where language = any(?::text[]) order by key",
        Seq(Seq(language, DefaultLanguage).distinct)
      ) { rs =>
        (rs.getString("key"), rs.getString("language"), rs.getString("text"))
      }
      val values = scala.collection.mutable.LinkedHashMap.empty[String, String]
      rows.foreach { case (key, lang, text) =>
        if (lang == language || !values.contains(key)) values.update(key, text)
      }
      JsObject(values.toSeq.map { case (key, text) => key -> JsString(text) })
    }

  private def parseAcceptLanguage(header: Option[String]): Seq[String] =
    header.toSeq.flatMap(_.split(",").toSeq.zipWithIndex).flatMap { case (part, index) =>
      val pieces = part.trim.split(";").map(_.trim)
      val code = pieces.headOption.getOrElse("").toLowerCase.split("-").headOption.getOrElse("")
      val q = pieces.drop(1).collectFirst {
        case value if value.startsWith("q=") => Try(value.stripPrefix("q=").toDouble).getOrElse(0.0)
      }.getOrElse(1.0)
      if (code.matches("^[a-z]{1,8}$")) Some((code, q, index)) else None
    }.sortBy { case (_, q, index) => (-q, index) }.map(_._1)
}

class AuthService(config: BackendConfig, db: Db, i18n: I18n, logger: EventLogger) {
  private val http = HttpClient.newHttpClient()
  @volatile private var processor: Option[DefaultJWTProcessor[SecurityContext]] = None
  private val Audience = "stackverse-api"
  private val AppRoles = Set("moderator", "admin")

  def optional(request: RequestHeader): Option[Caller] = {
    request.headers.get("Authorization") match {
      case None => None
      case Some(header) if !header.startsWith("Bearer ") => throw invalidToken("invalid_authorization_header")
      case Some(header) =>
        val caller = verify(header.stripPrefix("Bearer ").trim)
        val accountStatus = recordSeen(caller.username)
        if (accountStatus == "blocked") {
          logger.event("warn", "blocked_user_rejected", "denied", "Refused a request from a blocked account", "actor" -> JsString(caller.username))
          val language = i18n.resolve(Wire.first(request.queryString, "lang"), request.headers.get("Accept-Language"))
          throw new ForbiddenProblem(i18n.localize("error.account.blocked", language))
        }
        Some(caller)
    }
  }

  def requireCaller(request: RequestHeader): Caller =
    optional(request).getOrElse(throw new UnauthorizedProblem)

  def requireRole(request: RequestHeader, role: String): Caller = {
    val caller = requireCaller(request)
    if (!caller.roles.contains(role)) {
      logger.event("info", "authz_denied", "denied", "Denied a request lacking the required role", "actor" -> JsString(caller.username))
      throw new ForbiddenProblem("You do not have the role required for this operation.")
    }
    caller
  }

  def me(caller: Caller): JsObject =
    Wire.obj(
      "username" -> Some(JsString(caller.username)),
      "name" -> caller.name.map(JsString),
      "email" -> caller.email.map(JsString),
      "roles" -> Some(JsArray(caller.roles.filter(AppRoles.contains).sorted.map(JsString)))
    )

  private def verify(token: String): Caller = {
    try {
      val claims = jwtProcessor.process(token, null)
      val now = Instant.now()
      if (claims.getIssuer != config.oidcIssuerUri) throw new IllegalArgumentException("issuer")
      if (!Option(claims.getAudience).exists(_.asScala.contains(Audience))) throw new IllegalArgumentException("audience")
      if (Option(claims.getExpirationTime).forall(_.toInstant.isBefore(now))) throw new IllegalArgumentException("expired")
      if (Option(claims.getNotBeforeTime).exists(_.toInstant.isAfter(now))) throw new IllegalArgumentException("not_before")
      val username = Option(claims.getStringClaim("preferred_username")).filter(_.nonEmpty).getOrElse {
        throw new IllegalArgumentException("preferred_username")
      }
      val realmAccess = Option(claims.getJSONObjectClaim("realm_access"))
        .map(_.asScala.toMap.asInstanceOf[Map[String, Any]])
        .getOrElse(Map.empty[String, Any])
      val roles = realmAccess.get("roles") match {
        case Some(values: java.util.List[_]) => values.asScala.collect { case role: String => role }.toSeq
        case _ => Seq.empty[String]
      }
      Caller(username, roles, Option(claims.getStringClaim("name")), Option(claims.getStringClaim("email")))
    } catch {
      case _: Throwable => throw invalidToken("invalid_token")
    }
  }

  private def jwtProcessor: DefaultJWTProcessor[SecurityContext] = processor match {
    case Some(value) => value
    case None => synchronized {
      processor.getOrElse {
        val jwks = config.oidcJwksUri.getOrElse(discoverJwksUri())
        val source = new RemoteJWKSet[SecurityContext](new URL(jwks))
        val created = new DefaultJWTProcessor[SecurityContext]()
        created.setJWSKeySelector(new JWSVerificationKeySelector[SecurityContext](JWSAlgorithm.RS256, source))
        processor = Some(created)
        created
      }
    }
  }

  private def discoverJwksUri(): String = {
    val started = System.nanoTime()
    try {
      val request = HttpRequest.newBuilder(new URL(s"${config.oidcIssuerUri}/.well-known/openid-configuration").toURI).GET().build()
      val response = http.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() / 100 != 2) throw new RuntimeException(s"OIDC discovery answered ${response.statusCode()}")
      (Json.parse(response.body()) \ "jwks_uri").as[String]
    } catch {
      case error: Throwable =>
        logger.event(
          "error",
          "dependency_call_failed",
          "failure",
          "OIDC discovery failed",
          "dependency" -> JsString("keycloak"),
          "duration_ms" -> JsNumber((System.nanoTime() - started) / 1000000),
          "error_code" -> JsString("oidc_discovery_failed")
        )
        throw error
    }
  }

  private def recordSeen(username: String): String =
    db.withConnection { conn =>
      val now = Instant.now()
      db.one(
        conn,
        """insert into user_accounts (username, first_seen, last_seen, status)
          |values (?, ?, ?, 'active')
          |on conflict (username) do update set last_seen = excluded.last_seen
          |returning status""".stripMargin,
        Seq(username, now, now)
      )(_.getString("status")).get
    }

  private def invalidToken(code: String): UnauthorizedProblem = {
    logger.event("info", "jwt_validation_failed", "failure", "Rejected a bearer token", "error_code" -> JsString(code))
    new UnauthorizedProblem("Missing or invalid bearer token.")
  }
}

@Singleton
class StackverseBackend @Inject() (lifecycle: ApplicationLifecycle)(implicit ec: ExecutionContext) {
  val config: BackendConfig = BackendConfig.load()
  val logger = new EventLogger(config)
  val db = new Db(config, logger)
  val i18n = new I18n(db)
  val auth = new AuthService(config, db, i18n, logger)

  try {
    db.migrate()
    seedMessages()
    logger.event(
      "info",
      "application_start",
      "success",
      s"Stackverse backend (play-scala) listening on :${config.port}",
      "port" -> JsNumber(config.port),
      "db_host" -> JsString(config.dbHost),
      "db_port" -> JsNumber(config.dbPort),
      "db_name" -> JsString(config.dbName),
      "oidc_issuer" -> JsString(config.oidcIssuerUri),
      "oidc_jwks_uri" -> JsString(config.oidcJwksUri.getOrElse("(via OIDC discovery)")),
      "seed_messages_dir" -> JsString(config.seedMessagesDir.toString),
      "log_level" -> JsString(config.logLevel),
      "log_format" -> JsString(config.logFormat),
      "otel_enabled" -> JsBoolean(config.otelEnabled)
    )
  } catch {
    case error: Throwable =>
      logger.line("fatal", s"Failed to start: ${error.getClass.getSimpleName}")
      throw error
  }

  lifecycle.addStopHook { () =>
    logger.event("info", "application_stop", "success", "Shutting down Play Scala backend")
    db.close()
    logger.shutdown()
    scala.concurrent.Future.successful(())
  }

  def seedMessages(): Unit = {
    if (!Files.isDirectory(config.seedMessagesDir)) {
      throw new IllegalStateException(s"Message seed directory not found: ${config.seedMessagesDir}")
    }
    val files = Files.list(config.seedMessagesDir)
    try {
      files.iterator().asScala.filter(path => path.getFileName.toString.endsWith(".json")).toSeq.sortBy(_.getFileName.toString).foreach { file =>
        val language = file.getFileName.toString.stripSuffix(".json")
        val entries = Json.parse(Files.readString(file)).as[JsObject].fields.map { case (key, value) => key -> value.as[String] }
        val inserted = db.withConnection { conn =>
          val sql =
            """insert into messages (id, key, language, text, created_at, updated_at)
              |select gen_random_uuid(), key, ?, text, now(), now()
              |from unnest(?::text[], ?::text[]) as seed(key, text)
              |on conflict (key, language) do nothing""".stripMargin
          db.execute(conn, sql, Seq(language, entries.map(_._1), entries.map(_._2)))
        }
        logger.event(
          "info",
          "message_seed_imported",
          "success",
          s"Message seed '$language': $inserted inserted, ${entries.size - inserted} already present",
          "language" -> JsString(language),
          "inserted" -> JsNumber(inserted),
          "skipped" -> JsNumber(entries.size - inserted)
        )
      }
    } finally {
      files.close()
    }
  }
}
