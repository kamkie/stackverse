package dev.stackverse.http4s

import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.comcast.ip4s.{Host, Port}
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.RemoteJWKSet
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import fs2.Stream
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.{Logger as OtelLogger, Severity}
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import org.flywaydb.core.Flyway
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.postgresql.util.PGobject
import org.typelevel.ci.*

import java.net.{URI, URL}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.sql.{Connection, PreparedStatement, ResultSet, SQLException, Timestamp}
import java.time.{Instant, LocalDate, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.{Base64, UUID}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    app.use(_ => IO.never).as(ExitCode.Success)

  private def app: Resource[IO, Unit] =
    for {
      config <- Resource.eval(IO.blocking(BackendConfig.load()))
      logger <- Resource.make(IO(new EventLogger(config)))(logger => IO(logger.shutdown()))
      db <- Resource.make(IO.blocking(new Db(config, logger)))(db => IO.blocking(db.close()))
      _ <- Resource.make(IO.unit)(_ =>
        IO(logger.event("info", "application_stop", "success", "Shutting down Scala http4s backend"))
      )
      _ <- Resource.eval(IO.blocking(db.migrate()))
      _ <- Resource.eval(Boot.seedMessages(config, db, logger))
      i18n = I18n(db)
      auth = AuthService(config, db, i18n, logger)
      routes = StackverseRoutes(db, auth, i18n, logger)
      _ <- Resource.eval(IO(logger.event(
        "info",
        "application_start",
        "success",
        s"Stackverse backend (scala-http4s) listening on :${config.port}",
        "port" -> Json.fromInt(config.port),
        "db_host" -> Json.fromString(config.dbHost),
        "db_port" -> Json.fromInt(config.dbPort),
        "db_name" -> Json.fromString(config.dbName),
        "oidc_issuer" -> Json.fromString(config.oidcIssuerUri),
        "oidc_jwks_uri" -> Json.fromString(config.oidcJwksUri.getOrElse("(via OIDC discovery)")),
        "seed_messages_dir" -> Json.fromString(config.seedMessagesDir.toString),
        "log_level" -> Json.fromString(config.logLevel),
        "log_format" -> Json.fromString(config.logFormat),
        "otel_enabled" -> Json.fromBoolean(config.otelEnabled)
      )))
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString("0.0.0.0").get)
        .withPort(Port.fromInt(config.port).getOrElse(Port.fromInt(8080).get))
        .withHttpApp(routes.routes.orNotFound)
        .build
    } yield ()
}

object Boot {
  def seedMessages(config: BackendConfig, db: Db, logger: EventLogger): IO[Unit] =
    IO.blocking {
      if (!Files.isDirectory(config.seedMessagesDir)) {
        throw new IllegalStateException(s"Message seed directory not found: ${config.seedMessagesDir}")
      }
      val files = Files.list(config.seedMessagesDir)
      try {
        files.iterator().asScala.filter(_.getFileName.toString.endsWith(".json")).toSeq.sortBy(_.getFileName.toString).foreach { file =>
          val language = file.getFileName.toString.stripSuffix(".json")
          val entries = parse(Files.readString(file)).toOption.flatMap(_.asObject).getOrElse(JsonObject.empty).toIterable.toSeq.map {
            case (key, value) => key -> value.asString.getOrElse("")
          }
          val inserted = db.withConnection { conn =>
            db.execute(
              conn,
              """insert into messages (id, key, language, text, created_at, updated_at)
                |select gen_random_uuid(), key, ?, text, now(), now()
                |from unnest(?::text[], ?::text[]) as seed(key, text)
                |on conflict (key, language) do nothing""".stripMargin,
              Seq(language, entries.map(_._1), entries.map(_._2))
            )
          }
          logger.event(
            "info",
            "message_seed_imported",
            "success",
            s"Message seed '$language': $inserted inserted, ${entries.size - inserted} already present",
            "language" -> Json.fromString(language),
            "inserted" -> Json.fromInt(inserted),
            "skipped" -> Json.fromInt(entries.size - inserted)
          )
        }
      } finally {
        files.close()
      }
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
    Try(env(name, fallback.toString).toInt).getOrElse(throw new IllegalArgumentException(s"$name must be an integer"))

  private def seedDir: Path =
    Option(System.getenv("SEED_MESSAGES_DIR")).map(Paths.get(_)).getOrElse {
      val candidates = Seq(Paths.get("../../spec/messages"), Paths.get("spec/messages"), Paths.get("/app/spec/messages"))
      candidates.find(Files.isDirectory(_)).getOrElse(candidates.head)
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
    detail: Option[Json],
    createdAt: Instant
)

class Db(config: BackendConfig, logger: EventLogger) {
  val dataSource: HikariDataSource = {
    val hikari = HikariConfig()
    hikari.setJdbcUrl(s"jdbc:postgresql://${config.dbHost}:${config.dbPort}/${config.dbName}")
    hikari.setUsername(config.dbUser)
    hikari.setPassword(config.dbPassword)
    hikari.setMaximumPoolSize(10)
    hikari.setPoolName("stackverse-scala-http4s")
    HikariDataSource(hikari)
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
        "migration" -> Json.fromString(migration.filepath)
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
    case value: JsonObject =>
      val pg = PGobject()
      pg.setType("jsonb")
      pg.setValue(Json.fromJsonObject(value).noSpaces)
      ps.setObject(index, pg)
    case value: Json =>
      val pg = PGobject()
      pg.setType("jsonb")
      pg.setValue(value.noSpaces)
      ps.setObject(index, pg)
    case values: Seq[?] =>
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

  def audit(rs: ResultSet): AuditRow = AuditRow(
    id = rs.getObject("id", classOf[UUID]),
    actor = rs.getString("actor"),
    action = rs.getString("action"),
    targetType = rs.getString("target_type"),
    targetId = rs.getString("target_id"),
    detail = Option(rs.getObject("detail")).flatMap(value => parse(value.toString).toOption),
    createdAt = instant(rs, "created_at")
  )
}

case class I18n(db: Db) {
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

  def bundle(language: String): JsonObject =
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
      JsonObject.fromIterable(values.toSeq.map { case (key, text) => key -> Json.fromString(text) })
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

case class AuthService(config: BackendConfig, db: Db, i18n: I18n, logger: EventLogger) {
  private val http = HttpClient.newHttpClient()
  @volatile private var processor: Option[DefaultJWTProcessor[SecurityContext]] = None
  private val Audience = "stackverse-api"
  private val AppRoles = Set("moderator", "admin")

  def optional(request: Request[IO]): Option[Caller] =
    Wire.header(request, "Authorization") match {
      case None => None
      case Some(header) if !header.startsWith("Bearer ") => None
      case Some(header) =>
        val caller = verify(header.stripPrefix("Bearer ").trim)
        val accountStatus = recordSeen(caller.username)
        if (accountStatus == "blocked") {
          logger.event("warn", "blocked_user_rejected", "denied", "Refused a request from a blocked account", "actor" -> Json.fromString(caller.username))
          val language = i18n.resolve(Wire.first(Wire.query(request), "lang"), Wire.header(request, "Accept-Language"))
          throw ForbiddenProblem(i18n.localize("error.account.blocked", language))
        }
        Some(caller)
    }

  def requireCaller(request: Request[IO]): Caller =
    optional(request).getOrElse(throw UnauthorizedProblem())

  def requireRole(request: Request[IO], role: String): Caller = {
    val caller = requireCaller(request)
    if (!caller.roles.contains(role)) {
      logger.event("info", "authz_denied", "denied", "Denied a request lacking the required role", "actor" -> Json.fromString(caller.username))
      throw ForbiddenProblem("You do not have the role required for this operation.")
    }
    caller
  }

  def me(caller: Caller): Json =
    Wire.obj(
      "username" -> Some(Json.fromString(caller.username)),
      "name" -> caller.name.map(Json.fromString),
      "email" -> caller.email.map(Json.fromString),
      "roles" -> Some(Json.arr(caller.roles.filter(AppRoles.contains).sorted.map(Json.fromString)*))
    )

  private def verify(token: String): Caller =
    try {
      val claims = jwtProcessor.process(token, null)
      val now = Instant.now()
      if (claims.getIssuer != config.oidcIssuerUri) throw IllegalArgumentException("issuer")
      if (!Option(claims.getAudience).exists(_.asScala.contains(Audience))) throw IllegalArgumentException("audience")
      if (Option(claims.getExpirationTime).exists(_.toInstant.isBefore(now))) throw IllegalArgumentException("expired")
      if (Option(claims.getNotBeforeTime).exists(_.toInstant.isAfter(now))) throw IllegalArgumentException("not_before")
      val username = Option(claims.getStringClaim("preferred_username")).filter(_.nonEmpty).getOrElse {
        throw IllegalArgumentException("preferred_username")
      }
      val realmAccess = Option(claims.getJSONObjectClaim("realm_access"))
        .map(_.asScala.toMap.asInstanceOf[Map[String, Any]])
        .getOrElse(Map.empty[String, Any])
      val roles = realmAccess.get("roles") match {
        case Some(values: java.util.List[?]) => values.asScala.collect { case role: String => role }.toSeq
        case _ => Seq.empty[String]
      }
      Caller(username, roles, Option(claims.getStringClaim("name")), Option(claims.getStringClaim("email")))
    } catch {
      case _: Throwable => throw invalidToken("invalid_token")
    }

  private def jwtProcessor: DefaultJWTProcessor[SecurityContext] = processor match {
    case Some(value) => value
    case None => synchronized {
      processor.getOrElse {
        val jwks = config.oidcJwksUri.getOrElse(discoverJwksUri())
        val source = RemoteJWKSet[SecurityContext](URL(jwks))
        val created = DefaultJWTProcessor[SecurityContext]()
        created.setJWSKeySelector(JWSVerificationKeySelector[SecurityContext](JWSAlgorithm.RS256, source))
        processor = Some(created)
        created
      }
    }
  }

  private def discoverJwksUri(): String = {
    val started = System.nanoTime()
    try {
      val request = HttpRequest.newBuilder(URL(s"${config.oidcIssuerUri}/.well-known/openid-configuration").toURI).GET().build()
      val response = http.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() / 100 != 2) throw RuntimeException(s"OIDC discovery answered ${response.statusCode()}")
      parse(response.body()).toOption.flatMap(_.hcursor.get[String]("jwks_uri").toOption).getOrElse {
        throw RuntimeException("OIDC discovery response omitted jwks_uri")
      }
    } catch {
      case error: Throwable =>
        logger.event(
          "error",
          "dependency_call_failed",
          "failure",
          "OIDC discovery failed",
          "dependency" -> Json.fromString("keycloak"),
          "duration_ms" -> Json.fromLong((System.nanoTime() - started) / 1000000),
          "error_code" -> Json.fromString("oidc_discovery_failed")
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
    logger.event("info", "jwt_validation_failed", "failure", "Rejected a bearer token", "error_code" -> Json.fromString(code))
    UnauthorizedProblem("Missing or invalid bearer token.")
  }
}

case class StackverseRoutes(db: Db, auth: AuthService, i18n: I18n, logger: EventLogger) {
  import Wire.*

  private val TagPattern = "^[a-z0-9-]{1,30}$".r
  private val KeyPattern = "^[a-z0-9-]+(\\.[a-z0-9-]+)*$".r
  private val LanguagePattern = "^[a-z]{2}$".r

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "healthz" => handle(req)(IO.pure(jsonResponse(Status.Ok, Json.obj("status" -> Json.fromString("up")))))
    case req @ GET -> Root / "readyz" => handle(req)(readyz)
    case req @ GET -> Root / "api" / "v1" / "me" => handle(req)(me(req))
    case req @ GET -> Root / "api" / "v1" / "bookmarks" => handle(req)(listBookmarksV1(req))
    case req @ POST -> Root / "api" / "v1" / "bookmarks" => handle(req)(createBookmark(req))
    case req @ GET -> Root / "api" / "v2" / "bookmarks" => handle(req)(listBookmarksV2(req))
    case req @ POST -> Root / "api" / "v1" / "bookmarks" / id / "reports" => handle(req)(createReport(req, id))
    case req @ GET -> Root / "api" / "v1" / "bookmarks" / id => handle(req)(getBookmark(req, id))
    case req @ PUT -> Root / "api" / "v1" / "bookmarks" / id => handle(req)(updateBookmark(req, id))
    case req @ DELETE -> Root / "api" / "v1" / "bookmarks" / id => handle(req)(deleteBookmark(req, id))
    case req @ GET -> Root / "api" / "v1" / "tags" => handle(req)(listTags(req))
    case req @ GET -> Root / "api" / "v1" / "messages" / "bundle" => handle(req)(messageBundle(req))
    case req @ GET -> Root / "api" / "v1" / "messages" => handle(req)(listMessages(req))
    case req @ POST -> Root / "api" / "v1" / "messages" => handle(req)(createMessage(req))
    case req @ GET -> Root / "api" / "v1" / "messages" / id => handle(req)(getMessage(req, id))
    case req @ PUT -> Root / "api" / "v1" / "messages" / id => handle(req)(updateMessage(req, id))
    case req @ DELETE -> Root / "api" / "v1" / "messages" / id => handle(req)(deleteMessage(req, id))
    case req @ GET -> Root / "api" / "v1" / "reports" => handle(req)(listMyReports(req))
    case req @ PUT -> Root / "api" / "v1" / "reports" / id => handle(req)(updateMyReport(req, id))
    case req @ DELETE -> Root / "api" / "v1" / "reports" / id => handle(req)(withdrawReport(req, id))
    case req @ GET -> Root / "api" / "v1" / "admin" / "reports" => handle(req)(listReports(req))
    case req @ PUT -> Root / "api" / "v1" / "admin" / "reports" / id => handle(req)(resolveReport(req, id))
    case req @ PUT -> Root / "api" / "v1" / "admin" / "bookmarks" / id / "status" => handle(req)(setBookmarkStatus(req, id))
    case req @ GET -> Root / "api" / "v1" / "admin" / "users" => handle(req)(listUsers(req))
    case req @ GET -> Root / "api" / "v1" / "admin" / "users" / username => handle(req)(getUser(req, username))
    case req @ PUT -> Root / "api" / "v1" / "admin" / "users" / username / "status" => handle(req)(setUserStatus(req, username))
    case req @ GET -> Root / "api" / "v1" / "admin" / "audit-log" => handle(req)(auditLog(req))
    case req @ GET -> Root / "api" / "v1" / "admin" / "stats" => handle(req)(stats(req))
  }

  private def readyz: IO[Response[IO]] =
    IO.blocking {
      val started = System.nanoTime()
      try {
        db.withConnection(conn => db.one(conn, "select 1")(_.getInt(1)))
        jsonResponse(Status.Ok, Json.obj("status" -> Json.fromString("ready")))
      } catch {
        case error: Throwable =>
          logger.event(
            "warn",
            "dependency_call_failed",
            "failure",
            "Readiness lost: database unreachable",
            "dependency" -> Json.fromString("postgres"),
            "duration_ms" -> Json.fromLong((System.nanoTime() - started) / 1000000),
            "error_code" -> Json.fromString(sqlState(error).getOrElse("connection_error"))
          )
          jsonResponse(Status.ServiceUnavailable, Json.obj("status" -> Json.fromString("unavailable")))
      }
    }

  private def me(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    jsonResponse(Status.Ok, auth.me(auth.requireCaller(req)))
  }

  private def listBookmarksV1(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    val caller = auth.optional(req)
    val (page, size) = paging(query(req))
    val filters = parseListFilters(query(req))
    val (where, params) = listingWhere(caller, filters)
    val payload = db.withConnection { conn =>
      val rows = db.query(
        conn,
        s"""select * from bookmarks where $where
           |order by created_at desc, id desc
           |limit ? offset ?""".stripMargin,
        params ++ Seq(size, page * size)
      )(Rows.bookmark)
      val total = count(conn, s"select count(*) as count from bookmarks where $where", params)
      pagePayload(rows.map(Responses.bookmark), page, size, total)
    }
    jsonResponse(
      Status.Ok,
      payload,
      "Deprecation" -> "@1782864000",
      "Sunset" -> "Thu, 01 Jul 2027 00:00:00 GMT",
      "Link" -> "</api/v2/bookmarks>; rel=\"successor-version\""
    )
  }

  private def listBookmarksV2(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    val caller = auth.optional(req)
    val (_, size) = paging(query(req))
    val filters = parseListFilters(query(req))
    val cursor = single(query(req), "cursor").map(CursorCodec.decode)
    val (baseWhere, baseParams) = listingWhere(caller, filters)
    val (where, params) = cursor match {
      case Some(value) =>
        (s"$baseWhere and (created_at < ? or (created_at = ? and id < ?))", baseParams ++ Seq(value.createdAt, value.createdAt, value.id))
      case None => (baseWhere, baseParams)
    }
    val payload = db.withConnection { conn =>
      val fetched = db.query(
        conn,
        s"""select * from bookmarks where $where
           |order by created_at desc, id desc
           |limit ?""".stripMargin,
        params :+ (size + 1)
      )(Rows.bookmark)
      val items = fetched.take(size)
      val nextCursor = if (fetched.size > size && items.nonEmpty) {
        val last = items.last
        Some(CursorCodec.encode(BookmarkCursor(last.createdAt, last.id)))
      } else None
      obj(
        "items" -> Some(Json.arr(items.map(Responses.bookmark)*)),
        "nextCursor" -> nextCursor.map(Json.fromString)
      )
    }
    jsonResponse(Status.Ok, payload)
  }

  private def createBookmark(req: Request[IO]): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireCaller(req)
        val input = validateBookmarkInput(body)
        val now = Instant.now()
        val id = UUID.randomUUID()
        val row = db.withConnection { conn =>
          db.returning(
            conn,
            """insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)
              |values (?, ?, ?, ?, ?, ?::text[], ?, 'active', ?, ?) returning *""".stripMargin,
            Seq(id, caller.username, input.url, input.title, input.notes, input.tags, input.visibility, now, now)
          )(Rows.bookmark)
        }
        jsonResponse(Status.Created, Responses.bookmark(row), "Location" -> s"/api/v1/bookmarks/$id")
      }
    } yield response

  private def getBookmark(req: Request[IO], id: String): IO[Response[IO]] = IO.blocking {
    val caller = auth.optional(req)
    val uuid = parseUuid(id)
    val row = db.withConnection(conn => findBookmark(conn, uuid))
    val visible = row.exists(bookmark => bookmark.owner == caller.map(_.username).orNull || (bookmark.visibility == "public" && bookmark.status == "active"))
    if (!visible) throw NotFoundProblem()
    jsonResponse(Status.Ok, Responses.bookmark(row.get))
  }

  private def updateBookmark(req: Request[IO], id: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireCaller(req)
        val uuid = parseUuid(id)
        val input = validateBookmarkInput(body)
        val row = db.transaction { conn =>
          val existing = db.one(conn, "select * from bookmarks where id = ? for update", Seq(uuid))(Rows.bookmark)
          val bookmark = existing.filter(_.owner == caller.username).getOrElse(throw NotFoundProblem())
          if (bookmark.status == "hidden" && input.visibility == "public") {
            throw ConflictProblem("This bookmark was hidden by moderation and cannot be made public.", Some("error.bookmark.hidden-publish"))
          }
          db.returning(
            conn,
            """update bookmarks set url = ?, title = ?, notes = ?, tags = ?::text[], visibility = ?, updated_at = ?
              |where id = ? returning *""".stripMargin,
            Seq(input.url, input.title, input.notes, input.tags, input.visibility, Instant.now(), uuid)
          )(Rows.bookmark)
        }
        jsonResponse(Status.Ok, Responses.bookmark(row))
      }
    } yield response

  private def deleteBookmark(req: Request[IO], id: String): IO[Response[IO]] = IO.blocking {
    val caller = auth.requireCaller(req)
    val uuid = parseUuid(id)
    db.withConnection { conn =>
      val bookmark = findBookmark(conn, uuid).filter(_.owner == caller.username).getOrElse(throw NotFoundProblem())
      db.execute(conn, "delete from bookmarks where id = ?", Seq(bookmark.id))
    }
    Response[IO](Status.NoContent)
  }

  private def listTags(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    val caller = auth.requireCaller(req)
    val tags = db.withConnection { conn =>
      db.query(
        conn,
        """select tag, count(*) as count
          |from bookmarks, unnest(tags) as tag
          |where owner = ?
          |group by tag
          |order by count desc, tag asc""".stripMargin,
        Seq(caller.username)
      )(rs => Json.obj("tag" -> Json.fromString(rs.getString("tag")), "count" -> Json.fromLong(rs.getLong("count"))))
    }
    jsonResponse(Status.Ok, Json.obj("tags" -> Json.arr(tags*)))
  }

  private def listMessages(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    auth.optional(req)
    val (page, size) = paging(query(req))
    val key = single(query(req), "key")
    val language = single(query(req), "language")
    val q = single(query(req), "q")
    maxLength(q, 200, "q")
    val clauses = ArrayBuffer("true")
    val params = ArrayBuffer.empty[Any]
    key.foreach { value => clauses += "key = ?"; params += value }
    language.foreach { value => clauses += "language = ?"; params += value }
    q.filter(_.trim.nonEmpty).foreach { value =>
      val pattern = s"%${escapeLike(value)}%"
      clauses += "(key ilike ? escape '\\' or text ilike ? escape '\\')"
      params += pattern
      params += pattern
    }
    val where = clauses.mkString(" and ")
    val payload = db.withConnection { conn =>
      val rows = db.query(conn, s"select * from messages where $where order by key, language limit ? offset ?", params.toSeq ++ Seq(size, page * size))(Rows.message)
      val total = count(conn, s"select count(*) as count from messages where $where", params.toSeq)
      pagePayload(rows.map(Responses.message), page, size, total)
    }
    withEtag(req, payload)
  }

  private def messageBundle(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    auth.optional(req)
    val language = requestLanguage(req)
    withEtag(req, Json.obj("language" -> Json.fromString(language), "messages" -> Json.fromJsonObject(i18n.bundle(language))), "Content-Language" -> language)
  }

  private def getMessage(req: Request[IO], id: String): IO[Response[IO]] = IO.blocking {
    auth.optional(req)
    val uuid = parseUuid(id)
    val row = db.withConnection { conn =>
      db.one(conn, "select * from messages where id = ?", Seq(uuid))(Rows.message).getOrElse(throw NotFoundProblem())
    }
    withEtag(req, Responses.message(row))
  }

  private def createMessage(req: Request[IO]): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireRole(req, "admin")
        val input = validateMessageInput(body)
        val row = db.transaction { conn =>
          if (messageDuplicate(conn, input.key, input.language, None)) throw duplicateMessage(input)
          val inserted = try {
            db.returning(
              conn,
              """insert into messages (id, key, language, text, description, created_at, updated_at)
                |values (?, ?, ?, ?, ?, ?, ?) returning *""".stripMargin,
              Seq(UUID.randomUUID(), input.key, input.language, input.text, input.description, Instant.now(), Instant.now())
            )(Rows.message)
          } catch {
            case error: SQLException if sqlState(error).contains("23505") => throw duplicateMessage(input)
          }
          recordAudit(conn, caller.username, "message.created", "message", inserted.id.toString, messageSnapshot(inserted))
          inserted
        }
        logMessage("message_created", "Message created", caller.username, row)
        jsonResponse(Status.Created, Responses.message(row), "Location" -> s"/api/v1/messages/${row.id}")
      }
    } yield response

  private def updateMessage(req: Request[IO], id: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireRole(req, "admin")
        val uuid = parseUuid(id)
        val input = validateMessageInput(body)
        val row = db.transaction { conn =>
          db.one(conn, "select 1 from messages where id = ?", Seq(uuid))(_ => true).getOrElse(throw NotFoundProblem())
          if (messageDuplicate(conn, input.key, input.language, Some(uuid))) throw duplicateMessage(input)
          val updated = try {
            db.returning(
              conn,
              """update messages set key = ?, language = ?, text = ?, description = ?, updated_at = ?
                |where id = ? returning *""".stripMargin,
              Seq(input.key, input.language, input.text, input.description, Instant.now(), uuid)
            )(Rows.message)
          } catch {
            case error: SQLException if sqlState(error).contains("23505") => throw duplicateMessage(input)
          }
          recordAudit(conn, caller.username, "message.updated", "message", updated.id.toString, messageSnapshot(updated))
          updated
        }
        logMessage("message_updated", "Message updated", caller.username, row)
        jsonResponse(Status.Ok, Responses.message(row))
      }
    } yield response

  private def deleteMessage(req: Request[IO], id: String): IO[Response[IO]] = IO.blocking {
    val caller = auth.requireRole(req, "admin")
    val uuid = parseUuid(id)
    val row = db.transaction { conn =>
      val deleted = db.one(conn, "delete from messages where id = ? returning *", Seq(uuid))(Rows.message).getOrElse(throw NotFoundProblem())
      recordAudit(conn, caller.username, "message.deleted", "message", deleted.id.toString, messageSnapshot(deleted))
      deleted
    }
    logMessage("message_deleted", "Message deleted", caller.username, row)
    Response[IO](Status.NoContent)
  }

  private def createReport(req: Request[IO], id: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireCaller(req)
        val bookmarkId = parseUuid(id)
        val input = validateReportInput(body)
        val row = db.transaction { conn =>
          val visible = db.one(conn, "select visibility, status from bookmarks where id = ? for update", Seq(bookmarkId)) { rs =>
            rs.getString("visibility") == "public" && rs.getString("status") == "active"
          }.getOrElse(false)
          if (!visible) throw NotFoundProblem()
          val open = db.one(conn, "select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open'", Seq(bookmarkId, caller.username))(_ => true).getOrElse(false)
          if (open) throw ConflictProblem("You already have an open report on this bookmark.")
          try {
            db.returning(
              conn,
              """insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
                |values (?, ?, ?, ?, ?, 'open', ?) returning *""".stripMargin,
              Seq(UUID.randomUUID(), bookmarkId, caller.username, input.reason, input.comment, Instant.now())
            )(Rows.report)
          } catch {
            case error: SQLException if sqlState(error).contains("23505") =>
              throw ConflictProblem("You already have an open report on this bookmark.")
          }
        }
        logger.event(
          "info",
          "report_created",
          "success",
          "Report created on a public bookmark",
          "actor" -> Json.fromString(caller.username),
          "resource_type" -> Json.fromString("report"),
          "resource_id" -> Json.fromString(row.id.toString),
          "bookmark_id" -> Json.fromString(bookmarkId.toString),
          "reason" -> Json.fromString(row.reason)
        )
        jsonResponse(Status.Created, Responses.report(row))
      }
    } yield response

  private def listMyReports(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    val caller = auth.requireCaller(req)
    val (page, size) = paging(query(req))
    val status = validatedReportStatus(single(query(req), "status"))
    val (where, params) = status match {
      case Some(value) => ("reporter = ? and status = ?", Seq(caller.username, value))
      case None => ("reporter = ?", Seq(caller.username))
    }
    val payload = db.withConnection { conn =>
      val rows = db.query(conn, s"select * from reports where $where order by created_at desc, id desc limit ? offset ?", params ++ Seq(size, page * size))(Rows.report)
      val total = count(conn, s"select count(*) as count from reports where $where", params)
      pagePayload(rows.map(Responses.report), page, size, total)
    }
    jsonResponse(Status.Ok, payload)
  }

  private def updateMyReport(req: Request[IO], id: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireCaller(req)
        val uuid = parseUuid(id)
        val input = validateReportInput(body)
        val row = db.transaction { conn =>
          val report = ownReport(conn, caller.username, uuid)
          requireOpen(report)
          db.returning(conn, "update reports set reason = ?, comment = ? where id = ? returning *", Seq(input.reason, input.comment, uuid))(Rows.report)
        }
        logger.event(
          "info",
          "report_updated",
          "success",
          "Report updated by its reporter",
          "actor" -> Json.fromString(caller.username),
          "resource_type" -> Json.fromString("report"),
          "resource_id" -> Json.fromString(uuid.toString),
          "bookmark_id" -> Json.fromString(row.bookmarkId.toString),
          "reason" -> Json.fromString(row.reason)
        )
        jsonResponse(Status.Ok, Responses.report(row))
      }
    } yield response

  private def withdrawReport(req: Request[IO], id: String): IO[Response[IO]] = IO.blocking {
    val caller = auth.requireCaller(req)
    val uuid = parseUuid(id)
    val bookmarkId = db.transaction { conn =>
      val report = ownReport(conn, caller.username, uuid)
      requireOpen(report)
      db.execute(conn, "delete from reports where id = ?", Seq(uuid))
      report.bookmarkId
    }
    logger.event(
      "info",
      "report_withdrawn",
      "success",
      "Report withdrawn by its reporter",
      "actor" -> Json.fromString(caller.username),
      "resource_type" -> Json.fromString("report"),
      "resource_id" -> Json.fromString(uuid.toString),
      "bookmark_id" -> Json.fromString(bookmarkId.toString)
    )
    Response[IO](Status.NoContent)
  }

  private def listReports(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    auth.requireRole(req, "moderator")
    val (page, size) = paging(query(req))
    val status = validatedReportStatus(single(query(req), "status")).getOrElse("open")
    val payload = db.withConnection { conn =>
      val rows = db.query(conn, "select * from reports where status = ? order by created_at asc, id asc limit ? offset ?", Seq(status, size, page * size))(Rows.report)
      val total = count(conn, "select count(*) as count from reports where status = ?", Seq(status))
      pagePayload(rows.map(Responses.report), page, size, total)
    }
    jsonResponse(Status.Ok, payload)
  }

  private def resolveReport(req: Request[IO], id: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireRole(req, "moderator")
        val uuid = parseUuid(id)
        val validator = Validator()
        val target = body("resolution").flatMap(_.asString)
        validator.check(target.exists(Seq("open", "dismissed", "actioned").contains), "resolution", "validation.resolution.invalid")
        val note = body("note").flatMap(_.asString)
        validator.check(note.forall(_.length <= 1000), "note", "validation.resolution.note.too-long")
        validator.throwIfInvalid()
        val resolution = target.get
        val row = db.transaction { conn =>
          if (resolution == "actioned") {
            val bookmarkId = db.one(conn, "select bookmark_id from reports where id = ?", Seq(uuid))(_.getObject("bookmark_id", classOf[UUID])).getOrElse(throw NotFoundProblem())
            db.one(conn, "select id from bookmarks where id = ? for update", Seq(bookmarkId))(_ => true)
          }
          val report = db.one(conn, "select * from reports where id = ? for update", Seq(uuid))(Rows.report).getOrElse(throw NotFoundProblem())
          if (resolution == "open") {
            val conflict = db.one(
              conn,
              "select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open' and id <> ?",
              Seq(report.bookmarkId, report.reporter, uuid)
            )(_ => true).getOrElse(false)
            if (conflict) throw ConflictProblem("The reporter already has another open report on this bookmark.")
            val reopened = try {
              db.returning(
                conn,
                "update reports set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null where id = ? returning *",
                Seq(uuid)
              )(Rows.report)
            } catch {
              case error: SQLException if sqlState(error).contains("23505") =>
                throw ConflictProblem("The reporter already has another open report on this bookmark.")
            }
            recordAudit(conn, caller.username, "report.reopened", "report", uuid.toString, JsonObject("bookmarkId" -> Json.fromString(report.bookmarkId.toString)))
            logger.event(
              "info",
              "report_reopened",
              "success",
              "Report re-opened",
              "actor" -> Json.fromString(caller.username),
              "resource_type" -> Json.fromString("report"),
              "resource_id" -> Json.fromString(uuid.toString),
              "bookmark_id" -> Json.fromString(report.bookmarkId.toString)
            )
            reopened
          } else {
            val resolved = resolveOne(conn, report, resolution, caller.username, note, autoResolved = false)
            if (resolution == "actioned") {
              hideBookmark(conn, caller.username, report.bookmarkId, note)
              val siblings = db.query(
                conn,
                "select * from reports where bookmark_id = ? and status = 'open' and id <> ? order by id asc for update",
                Seq(report.bookmarkId, uuid)
              )(Rows.report)
              siblings.foreach(resolveOne(conn, _, "actioned", caller.username, note, autoResolved = true))
            }
            resolved
          }
        }
        jsonResponse(Status.Ok, Responses.report(row))
      }
    } yield response

  private def setBookmarkStatus(req: Request[IO], id: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireRole(req, "moderator")
        val uuid = parseUuid(id)
        val validator = Validator()
        val status = body("status").flatMap(_.asString)
        validator.check(status.exists(value => value == "active" || value == "hidden"), "status", "validation.bookmark-status.invalid")
        val note = body("note").flatMap(_.asString)
        validator.check(note.forall(_.length <= 1000), "note", "validation.bookmark-status.note.too-long")
        validator.throwIfInvalid()
        val row = db.transaction { conn =>
          val existing = db.one(conn, "select * from bookmarks where id = ? for update", Seq(uuid))(Rows.bookmark).getOrElse(throw NotFoundProblem())
          val updated = db.returning(conn, "update bookmarks set status = ?, updated_at = ? where id = ? returning *", Seq(status.get, Instant.now(), uuid))(Rows.bookmark)
          recordAudit(conn, caller.username, "bookmark.status-changed", "bookmark", uuid.toString, JsonObject("from" -> Json.fromString(existing.status), "to" -> Json.fromString(status.get), "note" -> note.fold(Json.Null)(Json.fromString)))
          logger.event(
            "info",
            "bookmark_status_changed",
            "success",
            "Bookmark moderation status changed",
            "actor" -> Json.fromString(caller.username),
            "resource_type" -> Json.fromString("bookmark"),
            "resource_id" -> Json.fromString(uuid.toString),
            "from" -> Json.fromString(existing.status),
            "to" -> Json.fromString(status.get)
          )
          updated
        }
        jsonResponse(Status.Ok, Responses.bookmark(row))
      }
    } yield response

  private def listUsers(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    auth.requireRole(req, "admin")
    val (page, size) = paging(query(req))
    val q = single(query(req), "q")
    maxLength(q, 100, "q")
    val status = single(query(req), "status")
    if (status.exists(value => value != "active" && value != "blocked")) throw BadRequestProblem(s"unknown status: ${status.get}")
    val clauses = ArrayBuffer("true")
    val params = ArrayBuffer.empty[Any]
    q.filter(_.trim.nonEmpty).foreach { value =>
      clauses += "u.username ilike ? escape '\\'"
      params += s"%${escapeLike(value)}%"
    }
    status.foreach { value =>
      clauses += "u.status = ?"
      params += value
    }
    val where = clauses.mkString(" and ")
    val payload = db.withConnection { conn =>
      val rows = db.query(conn, s"$WithBookmarkCount where $where order by u.last_seen desc, u.username asc limit ? offset ?", params.toSeq ++ Seq(size, page * size))(Rows.user)
      val total = count(conn, s"select count(*) as count from user_accounts u where $where", params.toSeq)
      pagePayload(rows.map(Responses.user), page, size, total)
    }
    jsonResponse(Status.Ok, payload)
  }

  private def getUser(req: Request[IO], username: String): IO[Response[IO]] = IO.blocking {
    auth.requireRole(req, "admin")
    val row = db.withConnection(conn => findUser(conn, username).getOrElse(throw NotFoundProblem()))
    jsonResponse(Status.Ok, Responses.user(row))
  }

  private def setUserStatus(req: Request[IO], username: String): IO[Response[IO]] =
    for {
      body <- jsonBody(req)
      response <- IO.blocking {
        val caller = auth.requireRole(req, "admin")
        val status = body("status").flatMap(_.asString).getOrElse(throw BadRequestProblem("status is required"))
        if (status != "active" && status != "blocked") throw BadRequestProblem("status is required")
        val reason = body("reason").flatMap(_.asString).map(_.trim)
        if (status == "blocked") {
          val validator = Validator()
          validator.check(reason.exists(_.nonEmpty), "reason", "validation.block.reason.required")
          validator.check(reason.forall(_.length <= 1000), "reason", "validation.block.reason.too-long")
          validator.throwIfInvalid()
          if (username == caller.username) throw ConflictProblem("Admins cannot block themselves.")
        }
        db.transaction { conn =>
          db.one(conn, "select username from user_accounts where username = ? for update", Seq(username))(_ => true).getOrElse(throw NotFoundProblem())
          if (status == "blocked") {
            db.execute(conn, "update user_accounts set status = 'blocked', blocked_reason = ? where username = ?", Seq(reason, username))
            recordAudit(conn, caller.username, "user.blocked", "user", username, JsonObject("reason" -> reason.fold(Json.Null)(Json.fromString)))
          } else {
            db.execute(conn, "update user_accounts set status = 'active', blocked_reason = null where username = ?", Seq(username))
            recordAudit(conn, caller.username, "user.unblocked", "user", username)
          }
        }
        logger.event(
          "info",
          if (status == "blocked") "user_blocked" else "user_unblocked",
          "success",
          if (status == "blocked") "User account blocked" else "User account unblocked",
          "actor" -> Json.fromString(caller.username),
          "resource_type" -> Json.fromString("user"),
          "resource_id" -> Json.fromString(username)
        )
        val row = db.withConnection(conn => findUser(conn, username).getOrElse(throw NotFoundProblem()))
        jsonResponse(Status.Ok, Responses.user(row))
      }
    } yield response

  private def auditLog(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    auth.requireRole(req, "admin")
    val (page, size) = paging(query(req))
    val clauses = ArrayBuffer("true")
    val params = ArrayBuffer.empty[Any]
    Seq("actor" -> "actor", "action" -> "action", "targetType" -> "target_type", "targetId" -> "target_id").foreach { case (param, column) =>
      single(query(req), param).foreach { value =>
        clauses += s"$column = ?"
        params += value
      }
    }
    single(query(req), "from").foreach { value =>
      clauses += "created_at >= ?"
      params += parseInstantParam(value, "from")
    }
    single(query(req), "to").foreach { value =>
      clauses += "created_at <= ?"
      params += parseInstantParam(value, "to")
    }
    val where = clauses.mkString(" and ")
    val payload = db.withConnection { conn =>
      val rows = db.query(conn, s"select * from audit_entries where $where order by created_at desc, id desc limit ? offset ?", params.toSeq ++ Seq(size, page * size))(Rows.audit)
      val total = count(conn, s"select count(*) as count from audit_entries where $where", params.toSeq)
      pagePayload(rows.map(Responses.audit), page, size, total)
    }
    jsonResponse(Status.Ok, payload)
  }

  private def stats(req: Request[IO]): IO[Response[IO]] = IO.blocking {
    auth.requireRole(req, "moderator")
    val today = LocalDate.now(ZoneOffset.UTC)
    val from = today.minusDays(29).atStartOfDay().toInstant(ZoneOffset.UTC)
    val payload = db.withConnection { conn =>
      val users = count(conn, "select count(*) as count from user_accounts")
      val bookmarks = count(conn, "select count(*) as count from bookmarks")
      val publicBookmarks = count(conn, "select count(*) as count from bookmarks where visibility = 'public'")
      val hiddenBookmarks = count(conn, "select count(*) as count from bookmarks where status = 'hidden'")
      val openReports = count(conn, "select count(*) as count from reports where status = 'open'")
      val createdPerDay = countPerDay(conn, "bookmarks", "created_at", from)
      val activePerDay = countPerDay(conn, "user_accounts", "last_seen", from)
      val topTags = db.query(
        conn,
        """select tag, count(*) as count from bookmarks, unnest(tags) as tag
          |group by tag order by count desc, tag asc limit 10""".stripMargin
      )(rs => Json.obj("tag" -> Json.fromString(rs.getString("tag")), "count" -> Json.fromLong(rs.getLong("count"))))
      val daily = (0 until 30).map { offset =>
        val date = today.minusDays(29 - offset.toLong)
        val bookmarksCreated = createdPerDay.get(date.toString).getOrElse(0L)
        val activeUsers = activePerDay.get(date.toString).getOrElse(0L)
        Json.obj(
          "date" -> Json.fromString(date.toString),
          "bookmarksCreated" -> Json.fromLong(bookmarksCreated),
          "activeUsers" -> Json.fromLong(activeUsers)
        )
      }
      Json.obj(
        "totals" -> Json.obj(
          "users" -> Json.fromLong(users),
          "bookmarks" -> Json.fromLong(bookmarks),
          "publicBookmarks" -> Json.fromLong(publicBookmarks),
          "hiddenBookmarks" -> Json.fromLong(hiddenBookmarks),
          "openReports" -> Json.fromLong(openReports)
        ),
        "daily" -> Json.arr(daily*),
        "topTags" -> Json.arr(topTags*)
      )
    }
    withEtag(req, payload)
  }

  private def handle(req: Request[IO])(response: IO[Response[IO]]): IO[Response[IO]] =
    response.handleErrorWith {
      case problem: ValidationProblem =>
        IO.blocking {
          logger.event(
            "info",
            "input_validation_failed",
            "failure",
            "Request validation failed",
            "error_code" -> Json.fromString("validation_failed"),
            "fields" -> Json.fromString(problem.violations.map(_.field).mkString(","))
          )
          val language = requestLanguage(req)
          val errors = problem.violations.map { violation =>
            Json.obj(
              "field" -> Json.fromString(violation.field),
              "messageKey" -> Json.fromString(violation.messageKey),
              "message" -> Json.fromString(i18n.localize(violation.messageKey, language))
            )
          }
          problemResponse(400, "Bad Request", Some("Request validation failed."), Some(errors))
        }
      case problem: ApiProblem =>
        IO.blocking {
          val detail = problem.detailKey.map(key => i18n.localize(key, requestLanguage(req))).orElse(problem.detail)
          problemResponse(problem.status, problem.title, detail)
        }
      case error: Throwable =>
        IO.blocking {
          sqlState(error).foreach { state =>
            logger.event(
              "error",
              "dependency_call_failed",
              "failure",
              "PostgreSQL call failed during a request",
              "dependency" -> Json.fromString("postgres"),
              "error_code" -> Json.fromString(state)
            )
          }
          problemResponse(500, "Internal Server Error", Some("An unexpected error occurred."))
        }
    }

  private case class BookmarkInput(url: String, title: String, notes: Option[String], tags: Seq[String], visibility: String)
  private case class MessageInput(key: String, language: String, text: String, description: Option[String])
  private case class ReportInput(reason: String, comment: Option[String])
  private case class ListFilters(tags: Seq[String], q: Option[String], visibility: Option[String])

  private def validateBookmarkInput(body: JsonObject): BookmarkInput = {
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

  private def validateMessageInput(body: JsonObject): MessageInput = {
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

  private def validateReportInput(body: JsonObject): ReportInput = {
    val validator = Validator()
    val reason = body("reason").flatMap(_.asString)
    validator.check(reason.exists(Seq("spam", "offensive", "broken-link", "other").contains), "reason", "validation.report.reason.invalid")
    val comment = body("comment").flatMap(_.asString)
    validator.check(comment.forall(_.length <= 1000), "comment", "validation.report.comment.too-long")
    validator.throwIfInvalid()
    ReportInput(reason.get, comment)
  }

  private def parseListFilters(queryParams: Map[String, Seq[String]]): ListFilters = {
    val q = single(queryParams, "q")
    maxLength(q, 200, "q")
    val visibility = single(queryParams, "visibility")
    if (visibility.exists(value => value != "private" && value != "public")) throw BadRequestProblem(s"unknown visibility: ${visibility.get}")
    ListFilters(validateQueryTags(multi(queryParams, "tag")), q, visibility)
  }

  private def validateQueryTags(values: Seq[String]): Seq[String] = {
    val tags = values.map(_.trim.toLowerCase)
    val validator = Validator()
    validator.check(tags.forall(tag => TagPattern.matches(tag)), "tag", "validation.tag.invalid")
    validator.throwIfInvalid()
    tags
  }

  private def listingWhere(caller: Option[Caller], filters: ListFilters): (String, Seq[Any]) = {
    val clauses = ArrayBuffer.empty[String]
    val params = ArrayBuffer.empty[Any]
    if (filters.visibility.contains("public")) {
      clauses += "visibility = 'public' and status = 'active'"
    } else {
      val username = caller.map(_.username).getOrElse(throw UnauthorizedProblem())
      clauses += "owner = ?"
      params += username
      filters.visibility.foreach { value =>
        clauses += "visibility = ?"
        params += value
      }
    }
    if (filters.tags.nonEmpty) {
      clauses += "tags @> ?::text[]"
      params += filters.tags
    }
    filters.q.filter(_.trim.nonEmpty).foreach { value =>
      val pattern = s"%${escapeLike(value)}%"
      clauses += "(title ilike ? escape '\\' or notes ilike ? escape '\\')"
      params += pattern
      params += pattern
    }
    (clauses.mkString(" and "), params.toSeq)
  }

  private def isHttpUrl(value: String): Boolean =
    Try(URI(value)).toOption.exists(uri => (uri.getScheme == "http" || uri.getScheme == "https") && Option(uri.getHost).exists(_.nonEmpty))

  private def requestLanguage(request: Request[IO]): String =
    i18n.resolve(first(query(request), "lang"), header(request, "Accept-Language"))

  private def pagePayload(items: Seq[Json], page: Int, size: Int, totalItems: Long): Json =
    Json.obj(
      "items" -> Json.arr(items*),
      "page" -> Json.fromInt(page),
      "size" -> Json.fromInt(size),
      "totalItems" -> Json.fromLong(totalItems),
      "totalPages" -> Json.fromInt(math.ceil(totalItems.toDouble / size.toDouble).toInt)
    )

  private def count(conn: Connection, sql: String, params: Seq[Any] = Seq.empty): Long =
    db.one(conn, sql, params)(_.getLong("count")).getOrElse(0L)

  private def findBookmark(conn: Connection, id: UUID): Option[BookmarkRow] =
    db.one(conn, "select * from bookmarks where id = ?", Seq(id))(Rows.bookmark)

  private val WithBookmarkCount =
    """select u.*, (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count
      |from user_accounts u""".stripMargin

  private def findUser(conn: Connection, username: String): Option[UserAccountRow] =
    db.one(conn, s"$WithBookmarkCount where u.username = ?", Seq(username))(Rows.user)

  private def messageDuplicate(conn: Connection, key: String, language: String, except: Option[UUID]): Boolean =
    except match {
      case Some(id) =>
        db.one(conn, "select 1 from messages where key = ? and language = ? and id <> ?", Seq(key, language, id))(_ => true).getOrElse(false)
      case None =>
        db.one(conn, "select 1 from messages where key = ? and language = ?", Seq(key, language))(_ => true).getOrElse(false)
    }

  private def duplicateMessage(input: MessageInput): ConflictProblem =
    ConflictProblem(s"A message with key '${input.key}' and language '${input.language}' already exists.")

  private def messageSnapshot(row: MessageRow): JsonObject =
    JsonObject.fromIterable(Seq(
      "key" -> Some(Json.fromString(row.key)),
      "language" -> Some(Json.fromString(row.language)),
      "text" -> Some(Json.fromString(row.text)),
      "description" -> row.description.map(Json.fromString)
    ).collect { case (key, Some(value)) => key -> value })

  private def logMessage(event: String, description: String, actor: String, row: MessageRow): Unit =
    logger.event(
      "info",
      event,
      "success",
      description,
      "actor" -> Json.fromString(actor),
      "resource_type" -> Json.fromString("message"),
      "resource_id" -> Json.fromString(row.id.toString),
      "message_key" -> Json.fromString(row.key),
      "language" -> Json.fromString(row.language)
    )

  private def recordAudit(conn: Connection, actor: String, action: String, targetType: String, targetId: String, detail: JsonObject = JsonObject.empty): Unit =
    db.execute(
      conn,
      "insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at) values (?, ?, ?, ?, ?, ?, ?)",
      Seq(UUID.randomUUID(), actor, action, targetType, targetId, if (detail.isEmpty) None else Some(detail), Instant.now())
    )

  private def ownReport(conn: Connection, reporter: String, id: UUID): ReportRow = {
    val row = db.one(conn, "select * from reports where id = ? for update", Seq(id))(Rows.report).getOrElse(throw NotFoundProblem())
    if (row.reporter != reporter) throw NotFoundProblem()
    row
  }

  private def requireOpen(report: ReportRow): Unit =
    if (report.status != "open") throw ConflictProblem("The report has already been resolved.")

  private def validatedReportStatus(value: Option[String]): Option[String] =
    value.map { status =>
      if (!Seq("open", "dismissed", "actioned").contains(status)) throw BadRequestProblem(s"unknown status: $status")
      status
    }

  private def resolveOne(conn: Connection, report: ReportRow, resolution: String, actor: String, note: Option[String], autoResolved: Boolean): ReportRow = {
    val updated = db.returning(
      conn,
      "update reports set status = ?, resolved_by = ?, resolved_at = ?, resolution_note = ? where id = ? returning *",
      Seq(resolution, actor, Instant.now(), note, report.id)
    )(Rows.report)
    recordAudit(
      conn,
      actor,
      "report.resolved",
      "report",
      report.id.toString,
      JsonObject(
        "bookmarkId" -> Json.fromString(report.bookmarkId.toString),
        "resolution" -> Json.fromString(resolution),
        "note" -> note.fold(Json.Null)(Json.fromString),
        "autoResolved" -> Json.fromBoolean(autoResolved)
      )
    )
    logger.event(
      "info",
      "report_resolved",
      "success",
      "Report resolved",
      "actor" -> Json.fromString(actor),
      "resource_type" -> Json.fromString("report"),
      "resource_id" -> Json.fromString(report.id.toString),
      "bookmark_id" -> Json.fromString(report.bookmarkId.toString),
      "resolution" -> Json.fromString(resolution),
      "auto_resolved" -> Json.fromBoolean(autoResolved)
    )
    updated
  }

  private def hideBookmark(conn: Connection, actor: String, bookmarkId: UUID, note: Option[String]): Unit = {
    val bookmark = findBookmark(conn, bookmarkId).getOrElse(throw NotFoundProblem())
    if (bookmark.status == "hidden") return
    db.execute(conn, "update bookmarks set status = 'hidden', updated_at = ? where id = ?", Seq(Instant.now(), bookmarkId))
    recordAudit(
      conn,
      actor,
      "bookmark.status-changed",
      "bookmark",
      bookmarkId.toString,
      JsonObject("from" -> Json.fromString("active"), "to" -> Json.fromString("hidden"), "note" -> note.fold(Json.Null)(Json.fromString))
    )
    logger.event(
      "info",
      "bookmark_status_changed",
      "success",
      "Bookmark hidden by an actioned report",
      "actor" -> Json.fromString(actor),
      "resource_type" -> Json.fromString("bookmark"),
      "resource_id" -> Json.fromString(bookmarkId.toString),
      "from" -> Json.fromString("active"),
      "to" -> Json.fromString("hidden")
    )
  }

  private def countPerDay(conn: Connection, table: String, column: String, from: Instant): Map[String, Long] =
    db.query(conn, s"select ($column at time zone 'UTC')::date::text as day, count(*) as count from $table where $column >= ? group by day", Seq(from)) { rs =>
      rs.getString("day") -> rs.getLong("count")
    }.toMap

  private def parseInstantParam(value: String, name: String): Instant =
    Try(Instant.parse(value)).getOrElse(throw BadRequestProblem(s"$name must be an RFC 3339 date-time"))

  private def sqlState(error: Throwable): Option[String] = error match {
    case sql: SQLException => Option(sql.getSQLState).orElse(Option(sql.getCause).flatMap(sqlState))
    case other => Option(other.getCause).flatMap(sqlState)
  }
}

object Wire {
  val JsonContentType = "application/json; charset=utf-8"
  val ProblemContentType = "application/problem+json"

  def obj(fields: (String, Option[Json])*): Json =
    Json.fromJsonObject(JsonObject.fromIterable(fields.collect { case (key, Some(value)) => key -> value }))

  def problemResponse(status: Int, title: String, detail: Option[String] = None, errors: Option[Seq[Json]] = None): Response[IO] =
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

  private def jsonResponse(status: Status, payload: Json, contentType: String, headers: (String, String)*): Response[IO] =
    val bytes = payload.noSpaces.getBytes(StandardCharsets.UTF_8)
    Response[IO](
      status = status,
      headers = Headers((Seq("Content-Type" -> contentType) ++ headers).map { case (key, value) => Header.Raw(CIString(key), value) }),
      body = Stream.emits(bytes).covary[IO]
    )

  def withEtag(request: Request[IO], payload: Json, headers: (String, String)*): Response[IO] = {
    val body = payload.noSpaces
    val digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))
    val etag = "\"" + Base64.getUrlEncoder.withoutPadding().encodeToString(digest) + "\""
    val cacheHeaders = Seq("ETag" -> etag, "Cache-Control" -> "no-cache") ++ headers
    val matches = header(request, "If-None-Match").exists(_.split(",").exists(_.trim == etag))
    if (matches) {
      Response[IO](Status.NotModified, headers = Headers(cacheHeaders.map { case (key, value) => Header.Raw(CIString(key), value) }))
    } else {
      jsonResponse(Status.Ok, payload, cacheHeaders*)
    }
  }

  def jsonBody(request: Request[IO]): IO[JsonObject] =
    request.as[String].map(raw => parse(raw).toOption.flatMap(_.asObject).getOrElse(JsonObject.empty)).handleError(_ => JsonObject.empty)

  def parseUuid(value: String): UUID =
    Try(UUID.fromString(value.toLowerCase)).getOrElse(throw NotFoundProblem())

  def query(request: Request[IO]): Map[String, Seq[String]] =
    request.uri.query.multiParams.view.mapValues(_.toSeq).toMap

  def header(request: Request[IO], name: String): Option[String] =
    request.headers.get(CIString(name)).map(_.head.value)

  def single(query: Map[String, Seq[String]], name: String): Option[String] =
    query.get(name).flatMap {
      case Nil => None
      case one :: Nil => Some(one)
      case _ => throw BadRequestProblem(s"$name must not be repeated")
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
    value.filter(_.nonEmpty).map(raw => Try(raw.toInt).getOrElse(throw BadRequestProblem(s"$name must be an integer"))).getOrElse(fallback)
}

object CursorCodec {
  def encode(cursor: BookmarkCursor): String = {
    val payload = Json.obj("createdAt" -> Json.fromString(cursor.createdAt.toString), "id" -> Json.fromString(cursor.id.toString)).noSpaces
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

class EventLogger(config: BackendConfig) {
  private val priorities = Map("debug" -> 10, "info" -> 20, "warn" -> 30, "error" -> 40, "fatal" -> 50)
  private val threshold = priorities.getOrElse(config.logLevel, 20)
  private val otelSdk: Option[OpenTelemetrySdk] =
    if (config.otelEnabled) Some(AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk) else None
  private val otelLogger: Option[OtelLogger] =
    otelSdk.map(_.getLogsBridge.loggerBuilder("stackverse-backend-scala-http4s").build())

  def event(level: String, event: String, outcome: String, message: String, fields: (String, Json)*): Unit =
    write(level, message, Some(event), Some(outcome), fields*)

  def line(level: String, message: String, fields: (String, Json)*): Unit =
    write(level, message, None, None, fields*)

  private def write(level: String, message: String, eventName: Option[String], outcome: Option[String], fields: (String, Json)*): Unit = {
    if (priorities.getOrElse(level, 20) < threshold) return
    val base = Seq(
      "timestamp" -> Json.fromString(DateTimeFormatter.ISO_INSTANT.format(Instant.now())),
      "level" -> Json.fromString(level),
      "message" -> Json.fromString(message)
    ) ++ eventName.map("event" -> Json.fromString(_)) ++ outcome.map("outcome" -> Json.fromString(_))
    val obj = JsonObject.fromIterable((base ++ fields).filterNot(_._2.isNull))
    if (config.logFormat == "text") {
      val suffix = fields.collect { case (key, value) if !value.isNull => s"$key=${value.noSpaces}" }
      println((Seq(level.toUpperCase, message) ++ suffix).mkString(" "))
    } else {
      println(Json.fromJsonObject(obj).noSpaces)
    }
    exportOtel(level, message, eventName, outcome, fields*)
  }

  def shutdown(): Unit =
    otelSdk.foreach(sdk => Try(sdk.close()))

  private def exportOtel(level: String, message: String, eventName: Option[String], outcome: Option[String], fields: (String, Json)*): Unit =
    otelLogger.foreach { logger =>
      try {
        val record = logger.logRecordBuilder()
          .setTimestamp(Instant.now())
          .setSeverity(severity(level))
          .setBody(message)
        eventName.foreach(value => record.setAttribute(AttributeKey.stringKey("event"), value))
        outcome.foreach(value => record.setAttribute(AttributeKey.stringKey("outcome"), value))
        fields.foreach {
          case (key, value) if value.isString => record.setAttribute(AttributeKey.stringKey(key), value.asString.get)
          case (_, value) if value.isNull => ()
          case (key, value) => record.setAttribute(AttributeKey.stringKey(key), value.noSpaces)
        }
        record.emit()
      } catch {
        case _: Throwable => ()
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
