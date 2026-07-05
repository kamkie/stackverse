package dev.stackverse.backend

import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.RemoteJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.SignedJWT
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.logstash.logback.encoder.LogstashEncoder
import org.flywaydb.core.Flyway
import org.postgresql.util.PGobject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.math.ceil

private const val AUDIENCE = "stackverse-api"
private const val DEFAULT_LANGUAGE = "en"
private const val DEPRECATION = "@1782864000"
private const val SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT"
private const val SUCCESSOR_LINK = "</api/v2/bookmarks>; rel=\"successor-version\""
private val PROBLEM_JSON = ContentType.parse("application/problem+json")
private val TAG_PATTERN = Regex("^[a-z0-9-]{1,30}$")
private val MESSAGE_KEY_PATTERN = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)*$")
private val LANGUAGE_PATTERN = Regex("^[a-z]{2}$")
private val REPORT_REASONS = setOf("spam", "offensive", "broken-link", "other")
private val REPORT_STATUSES = setOf("open", "dismissed", "actioned")
private val BOOKMARK_STATUSES = setOf("active", "hidden")
private val VISIBILITIES = setOf("private", "public")
private val IDENTITY_KEY = AttributeKey<Identity>("stackverse.identity")

fun main() {
    val config = Config.load()
    configureLogging(config)
    val mapper = jsonMapper()
    val logger = LoggerFactory.getLogger("dev.stackverse.backend.Application")
    val dataSource = hikari(config)
    val database = Database(dataSource)
    val context = AppContext(config, mapper, database, logger)

    try {
        context.migrate()
        context.seedMessages()
        embeddedServer(Netty, port = config.port) {
            stackverseModule(context)
        }.start(wait = true)
    } catch (error: Throwable) {
        logger.atLevel(Level.ERROR)
            .addKeyValue("event", "application_start")
            .addKeyValue("outcome", "failure")
            .addKeyValue("error_code", "startup_failed")
            .log("Application refused to start", error)
        throw error
    } finally {
        dataSource.close()
    }
}

fun Application.stackverseModule(context: AppContext) {
    install(ContentNegotiation) {
        jackson {
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            setSerializationInclusion(JsonInclude.Include.NON_NULL)
        }
    }
    install(StatusPages) {
        exception<ValidationProblem> { call, cause ->
            context.logger.logEvent(Level.INFO, "input_validation_failed", "failure", "Input validation failed")
            call.respondProblem(
                context,
                Problem(
                    title = "Bad Request",
                    status = 400,
                    errors = cause.violations.map {
                        FieldError(it.field, it.messageKey, context.localizer.localize(it.messageKey, call))
                    },
                ),
                HttpStatusCode.BadRequest,
            )
        }
        exception<ApiProblem> { call, cause ->
            call.respondProblem(
                context,
                Problem(
                    title = cause.title,
                    status = cause.status.value,
                    detail = cause.detailKey?.let { context.localizer.localize(it, call) } ?: cause.detail,
                ),
                cause.status,
            )
        }
        exception<BadRequestException> { call, _ ->
            call.respondProblem(context, Problem("Bad Request", 400, detail = "Invalid request."), HttpStatusCode.BadRequest)
        }
        exception<Throwable> { call, cause ->
            context.logger.atLevel(Level.ERROR)
                .addKeyValue("event", "request_failed")
                .addKeyValue("outcome", "failure")
                .addKeyValue("error_code", cause.javaClass.simpleName)
                .log("Unhandled request failure", cause)
            call.respondProblem(context, Problem("Internal Server Error", 500), HttpStatusCode.InternalServerError)
        }
    }

    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        val identity = context.jwtAuthenticator.authenticate(call)
        if (identity != null) {
            val account = context.accounts.recordSeen(identity.username)
            if (account.status == "blocked") {
                context.logger.logEvent(
                    Level.WARN,
                    "blocked_user_rejected",
                    "denied",
                    "Refused a request from a blocked account",
                    "actor" to identity.username,
                )
                throw ApiProblem(HttpStatusCode.Forbidden, "Forbidden", detailKey = "error.account.blocked")
            }
            call.attributes.put(IDENTITY_KEY, identity)
        }
    }

    environment.monitor.subscribe(ApplicationStarted) {
        context.logger.logEvent(
            Level.INFO,
            "application_start",
            "success",
            "Application listening",
            "port" to context.config.port,
            "db_host" to context.config.dbHost,
            "db_port" to context.config.dbPort,
            "db_name" to context.config.dbName,
            "oidc_issuer_uri" to context.config.issuerUri,
            "oidc_jwks_uri_set" to context.config.jwksUri.isNotBlank(),
            "log_format" to context.config.logFormat,
            "log_level" to context.config.logLevel,
        )
    }
    environment.monitor.subscribe(ApplicationStopped) {
        context.logger.logEvent(Level.INFO, "application_stop", "success", "Application stopped")
    }

    routing {
        get("/healthz") {
            call.respondText("ok")
        }
        get("/readyz") {
            if (context.database.ready()) {
                call.respondText("ok")
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable)
            }
        }

        get("/api/v1/me") {
            val identity = call.requireIdentity()
            call.respond(MeResponse(identity.username, identity.name, identity.email, identity.applicationRoles()))
        }

        get("/api/v1/tags") {
            val identity = call.requireIdentity()
            call.respond(TagListResponse(context.bookmarks.tags(identity.username)))
        }

        route("/api/v1/bookmarks") {
            get {
                val page = call.pageParam()
                val size = call.sizeParam()
                val query = call.bookmarkQuery()
                val identity = call.identity()
                val result = context.bookmarks.listOffset(identity?.username, query, page, size)
                call.response.headers.append("Deprecation", DEPRECATION)
                call.response.headers.append("Sunset", SUNSET)
                call.response.headers.append(HttpHeaders.Link, SUCCESSOR_LINK)
                call.respond(result)
            }
            post {
                val identity = call.requireIdentity()
                val bookmark = context.bookmarks.create(identity.username, call.receiveBody())
                call.response.headers.append(HttpHeaders.Location, "/api/v1/bookmarks/${bookmark.id}")
                call.respond(HttpStatusCode.Created, bookmark)
            }
            get("{id}") {
                call.respond(context.bookmarks.get(call.identity()?.username, call.uuidPath("id")))
            }
            put("{id}") {
                val identity = call.requireIdentity()
                call.respond(context.bookmarks.update(identity.username, call.uuidPath("id"), call.receiveBody()))
            }
            delete("{id}") {
                val identity = call.requireIdentity()
                context.bookmarks.delete(identity.username, call.uuidPath("id"))
                call.respond(HttpStatusCode.NoContent)
            }
            post("{id}/reports") {
                val identity = call.requireIdentity()
                val report = context.moderation.report(identity.username, call.uuidPath("id"), call.receiveBody())
                call.respond(HttpStatusCode.Created, report)
            }
        }

        get("/api/v2/bookmarks") {
            val size = call.sizeParam()
            val query = call.bookmarkQuery()
            val cursor = call.request.queryParameters["cursor"]?.let { BookmarkCursor.decode(it) }
            val slice = context.bookmarks.listKeyset(call.identity()?.username, query, cursor, size)
            call.respond(slice)
        }

        route("/api/v1/reports") {
            get {
                val identity = call.requireIdentity()
                val page = call.pageParam()
                val size = call.sizeParam()
                val status = call.optionalReportStatus()
                call.respond(context.moderation.listMine(identity.username, status, page, size))
            }
            put("{id}") {
                val identity = call.requireIdentity()
                call.respond(context.moderation.updateMine(identity.username, call.uuidPath("id"), call.receiveBody()))
            }
            delete("{id}") {
                val identity = call.requireIdentity()
                context.moderation.withdraw(identity.username, call.uuidPath("id"))
                call.respond(HttpStatusCode.NoContent)
            }
        }

        route("/api/v1/admin/reports") {
            get {
                call.requireRole("moderator", context)
                val page = call.pageParam()
                val size = call.sizeParam()
                val status = call.optionalReportStatus() ?: "open"
                call.respond(context.moderation.listAdmin(status, page, size))
            }
            put("{id}") {
                val identity = call.requireRole("moderator", context)
                call.respond(context.moderation.resolve(identity.username, call.uuidPath("id"), call.receiveBody()))
            }
        }

        put("/api/v1/admin/bookmarks/{id}/status") {
            val identity = call.requireRole("moderator", context)
            call.respond(context.moderation.setBookmarkStatus(identity.username, call.uuidPath("id"), call.receiveBody()))
        }

        route("/api/v1/admin/users") {
            get {
                call.requireRole("admin", context)
                val page = call.pageParam()
                val size = call.sizeParam()
                val q = call.request.queryParameters["q"]?.also { requireMaxLength(it, 100, "q") }
                val status = call.request.queryParameters["status"]?.let {
                    if (it !in setOf("active", "blocked")) throw ValidationProblem.of("status", "validation.user.status.invalid")
                    it
                }
                call.respond(context.accounts.list(q, status, page, size))
            }
            get("{username}") {
                call.requireRole("admin", context)
                call.respond(context.accounts.get(call.parameters["username"].orEmpty()))
            }
            put("{username}/status") {
                val identity = call.requireRole("admin", context)
                call.respond(context.accounts.setStatus(identity.username, call.parameters["username"].orEmpty(), call.receiveBody()))
            }
        }

        get("/api/v1/admin/audit-log") {
            call.requireRole("admin", context)
            val page = call.pageParam()
            val size = call.sizeParam()
            val filter = AuditFilter(
                actor = call.request.queryParameters["actor"],
                action = call.request.queryParameters["action"],
                targetType = call.request.queryParameters["targetType"],
                targetId = call.request.queryParameters["targetId"],
                from = call.request.queryParameters["from"]?.let { parseInstantParam(it, "from") },
                to = call.request.queryParameters["to"]?.let { parseInstantParam(it, "to") },
            )
            call.respond(context.audit.list(filter, page, size))
        }

        get("/api/v1/admin/stats") {
            call.requireRole("moderator", context)
            call.respondJsonWithEtag(context, context.stats.stats(), cacheControl = true)
        }

        route("/api/v1/messages") {
            get {
                val page = call.pageParam()
                val size = call.sizeParam()
                val q = call.request.queryParameters["q"]?.also { requireMaxLength(it, 200, "q") }
                val response = context.messages.list(
                    key = call.request.queryParameters["key"],
                    language = call.request.queryParameters["language"],
                    q = q,
                    page = page,
                    size = size,
                )
                call.respondJsonWithEtag(context, response, cacheControl = true)
            }
            post {
                val identity = call.requireRole("admin", context)
                val message = context.messages.create(identity.username, call.receiveBody())
                call.response.headers.append(HttpHeaders.Location, "/api/v1/messages/${message.id}")
                call.respond(HttpStatusCode.Created, message)
            }
            get("bundle") {
                val language = context.localizer.resolve(
                    call.request.queryParameters["lang"],
                    call.request.headers[HttpHeaders.AcceptLanguage],
                )
                call.response.headers.append(HttpHeaders.ContentLanguage, language)
                call.respondJsonWithEtag(context, MessageBundleResponse(language, context.messages.bundle(language)), cacheControl = true)
            }
            get("{id}") {
                call.respondJsonWithEtag(context, context.messages.get(call.uuidPath("id")), cacheControl = true)
            }
            put("{id}") {
                val identity = call.requireRole("admin", context)
                call.respond(context.messages.update(identity.username, call.uuidPath("id"), call.receiveBody()))
            }
            delete("{id}") {
                val identity = call.requireRole("admin", context)
                context.messages.delete(identity.username, call.uuidPath("id"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

data class Config(
    val port: Int,
    val dbHost: String,
    val dbPort: String,
    val dbName: String,
    val dbUser: String,
    val dbPassword: String,
    val issuerUri: String,
    val jwksUri: String,
    val seedMessagesDir: String,
    val logLevel: String,
    val logFormat: String,
) {
    val jdbcUrl: String = "jdbc:postgresql://$dbHost:$dbPort/$dbName"

    companion object {
        fun load(): Config = Config(
            port = env("PORT", "8080").toInt(),
            dbHost = env("DB_HOST", "localhost"),
            dbPort = env("DB_PORT", "5432"),
            dbName = env("DB_NAME", "stackverse"),
            dbUser = env("DB_USER", "stackverse"),
            dbPassword = env("DB_PASSWORD", "stackverse"),
            issuerUri = env("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse"),
            jwksUri = env("OIDC_JWKS_URI", ""),
            seedMessagesDir = env("SEED_MESSAGES_DIR", "../../spec/messages"),
            logLevel = env("LOG_LEVEL", "info"),
            logFormat = env("LOG_FORMAT", "json"),
        )

        private fun env(name: String, fallback: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback
    }
}

class AppContext(
    val config: Config,
    val mapper: ObjectMapper,
    val database: Database,
    val logger: Logger,
) {
    val audit = AuditRepository(database, mapper)
    val bookmarks = BookmarkRepository(database)
    val messages = MessageRepository(database, audit, mapper, logger)
    val accounts = AccountRepository(database, audit, logger)
    val moderation = ModerationRepository(database, bookmarks, audit, logger)
    val stats = StatsRepository(database)
    val localizer = MessageLocalizer(messages)
    val jwtAuthenticator = JwtAuthenticator(config, mapper, logger)

    fun migrate() {
        val result = Flyway.configure()
            .dataSource(database.dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        result.migrations.forEach {
            logger.logEvent(
                Level.INFO,
                "db_migration_applied",
                "success",
                "Database migration applied",
                "version" to it.version,
                "description" to it.description,
            )
        }
    }

    fun seedMessages() = runBlocking {
        val dir = Path.of(config.seedMessagesDir)
        check(Files.isDirectory(dir)) {
            "Message seed directory not found: ${dir.toAbsolutePath()}. Set SEED_MESSAGES_DIR to the spec/messages directory."
        }
        dir.listDirectoryEntries().filter { it.extension == "json" }.sorted().forEach { file ->
            val language = file.nameWithoutExtension
            val entries: Map<String, String> = mapper.readValue(Files.readString(file))
            val inserted = messages.seed(language, entries)
            logger.logEvent(
                Level.INFO,
                "message_seed_imported",
                "success",
                "Message seed imported",
                "language" to language,
                "inserted" to inserted,
                "skipped" to entries.size - inserted,
            )
        }
    }
}

class Database(val dataSource: HikariDataSource) {
    suspend fun ready(): Boolean = runCatching {
        read {
            prepareStatement("select 1").use { statement ->
                statement.executeQuery().use { rs -> rs.next() }
            }
        }
    }.getOrDefault(false)

    suspend fun <T> read(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.block()
        }
    }

    suspend fun <T> transaction(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val result = connection.block()
                connection.commit()
                result
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }
}

data class Identity(val username: String, val name: String?, val email: String?, val roles: List<String>) {
    fun applicationRoles(): List<String> = roles.filter { it == "moderator" || it == "admin" }
}

data class MeResponse(val username: String, val name: String?, val email: String?, val roles: List<String>)
data class BookmarkRequest(val url: String? = null, val title: String? = null, val notes: String? = null, val tags: List<String>? = null, val visibility: String? = null)
data class BookmarkResponse(
    val id: UUID,
    val url: String,
    val title: String,
    val notes: String?,
    val tags: List<String>,
    val visibility: String,
    val status: String,
    val owner: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
data class BookmarkListQuery(val tags: List<String>, val q: String?, val visibility: String?)
data class BookmarkCursorPageResponse(val items: List<BookmarkResponse>, val nextCursor: String?)
data class MessageRequest(val key: String? = null, val language: String? = null, val text: String? = null, val description: String? = null)
data class MessageResponse(val id: UUID, val key: String, val language: String, val text: String, val description: String?, val createdAt: Instant, val updatedAt: Instant)
data class MessageBundleResponse(val language: String, val messages: Map<String, String>)
data class ReportRequest(val reason: String? = null, val comment: String? = null)
data class ReportResolutionRequest(val resolution: String? = null, val note: String? = null)
data class BookmarkStatusRequest(val status: String? = null, val note: String? = null)
data class ReportResponse(
    val id: UUID,
    val bookmarkId: UUID,
    val reporter: String,
    val reason: String,
    val comment: String?,
    val status: String,
    val resolvedBy: String?,
    val resolvedAt: Instant?,
    val resolutionNote: String?,
    val createdAt: Instant,
)
data class UserStatusRequest(val status: String? = null, val reason: String? = null)
data class UserAccountResponse(val username: String, val firstSeen: Instant, val lastSeen: Instant, val status: String, val blockedReason: String?, val bookmarkCount: Long)
data class AuditEntryResponse(val id: UUID, val actor: String, val action: String, val targetType: String, val targetId: String, val detail: Map<String, Any?>?, val createdAt: Instant)
data class AuditFilter(val actor: String?, val action: String?, val targetType: String?, val targetId: String?, val from: Instant?, val to: Instant?)
data class TagCountResponse(val tag: String, val count: Long)
data class TagListResponse(val tags: List<TagCountResponse>)
data class StatsTotals(val users: Long, val bookmarks: Long, val publicBookmarks: Long, val hiddenBookmarks: Long, val openReports: Long)
data class DailyStat(val date: LocalDate, val bookmarksCreated: Long, val activeUsers: Long)
data class AdminStatsResponse(val totals: StatsTotals, val daily: List<DailyStat>, val topTags: List<TagCountResponse>)
data class PageResponse<T>(val items: List<T>, val page: Int, val size: Int, val totalItems: Long, val totalPages: Int)
data class Problem(val title: String, val status: Int, val detail: String? = null, val type: String = "about:blank", val errors: List<FieldError>? = null)
data class FieldError(val field: String, val messageKey: String, val message: String)
data class FieldViolation(val field: String, val messageKey: String)

class ValidationProblem(val violations: List<FieldViolation>) : RuntimeException("Validation failed") {
    companion object {
        fun of(field: String, messageKey: String) = ValidationProblem(listOf(FieldViolation(field, messageKey)))
    }
}

class Validator {
    private val violations = mutableListOf<FieldViolation>()

    fun reject(field: String, messageKey: String) {
        violations += FieldViolation(field, messageKey)
    }

    fun check(condition: Boolean, field: String, messageKey: String) {
        if (!condition) reject(field, messageKey)
    }

    fun throwIfInvalid() {
        if (violations.isNotEmpty()) throw ValidationProblem(violations)
    }
}

class ApiProblem(val status: HttpStatusCode, val title: String, val detail: String? = null, val detailKey: String? = null) :
    RuntimeException(detail ?: title)

class BookmarkCursor(val createdAt: Instant, val id: UUID) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$createdAt|$id".toByteArray(Charsets.UTF_8))

    companion object {
        fun of(bookmark: BookmarkResponse) = BookmarkCursor(bookmark.createdAt, bookmark.id)

        fun decode(value: String): BookmarkCursor = try {
            val decoded = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
            val parts = decoded.split('|', limit = 2)
            require(parts.size == 2)
            BookmarkCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]))
        } catch (_: Exception) {
            throw ApiProblem(HttpStatusCode.BadRequest, "Bad Request", detail = "The cursor is malformed or unresolvable.")
        }
    }
}

class BookmarkRepository(private val db: Database) {
    suspend fun create(owner: String, request: BookmarkRequest): BookmarkResponse = db.transaction {
        val input = validateBookmark(request)
        val id = UUID.randomUUID()
        val now = nowUtc()
        execute(
            """
            insert into bookmarks (id, owner, url, title, notes, visibility, status, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            id, owner, input.url, input.title, input.notes, input.visibility.dbValue(), now, now,
        )
        replaceTags(id, input.tags)
        findBookmarkOn(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    suspend fun get(caller: String?, id: UUID): BookmarkResponse = db.read {
        val bookmark = findBookmarkOn(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (bookmark.owner != caller && !(bookmark.visibility == "public" && bookmark.status == "active")) {
            throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        }
        bookmark
    }

    suspend fun update(caller: String, id: UUID, request: BookmarkRequest): BookmarkResponse = db.transaction {
        val current = lockBookmarkOn(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (current.owner != caller) throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        val input = validateBookmark(request)
        if (current.status == "hidden" && input.visibility == "public") {
            throw ApiProblem(
                HttpStatusCode.Conflict,
                "Conflict",
                detail = "This bookmark was hidden by moderation and cannot be made public.",
                detailKey = "error.bookmark.hidden-publish",
            )
        }
        execute(
            """
            update bookmarks
            set url = ?, title = ?, notes = ?, visibility = ?, updated_at = ?
            where id = ?
            """.trimIndent(),
            input.url, input.title, input.notes, input.visibility.dbValue(), nowUtc(), id,
        )
        replaceTags(id, input.tags)
        findBookmarkOn(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    suspend fun delete(caller: String, id: UUID) = db.transaction {
        val bookmark = findBookmarkOn(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (bookmark.owner != caller) throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        execute("delete from bookmarks where id = ?", id)
    }

    suspend fun listOffset(caller: String?, query: BookmarkListQuery, page: Int, size: Int): PageResponse<BookmarkResponse> = db.read {
        val scope = bookmarkWhere(caller, query)
        val total = queryLong("select count(*) from bookmarks b where ${scope.sql}", scope.args)
        val connection = this
        val items = query(
            """
            select b.* from bookmarks b
            where ${scope.sql}
            order by b.created_at desc, b.id desc
            limit ? offset ?
            """.trimIndent(),
            scope.args + listOf(size, page * size),
        ) { connection.toBookmark(it) }
        PageResponse(items, page, size, total, pages(total, size))
    }

    suspend fun listKeyset(caller: String?, query: BookmarkListQuery, cursor: BookmarkCursor?, size: Int): BookmarkCursorPageResponse = db.read {
        var scope = bookmarkWhere(caller, query)
        if (cursor != null) {
            scope = scope.and(
                "(b.created_at < ? or (b.created_at = ? and b.id < ?))",
                cursor.createdAt,
                cursor.createdAt,
                cursor.id,
            )
        }
        val connection = this
        val fetched = query(
            """
            select b.* from bookmarks b
            where ${scope.sql}
            order by b.created_at desc, b.id desc
            limit ?
            """.trimIndent(),
            scope.args + listOf(size + 1),
        ) { connection.toBookmark(it) }
        val items = fetched.take(size)
        BookmarkCursorPageResponse(items, if (fetched.size > size) BookmarkCursor.of(items.last()).encode() else null)
    }

    suspend fun tags(owner: String): List<TagCountResponse> = db.read {
        query(
            """
            select bt.tag, count(*) as count
            from bookmark_tags bt
            join bookmarks b on b.id = bt.bookmark_id
            where b.owner = ?
            group by bt.tag
            order by count(*) desc, bt.tag
            """.trimIndent(),
            listOf(owner),
        ) { TagCountResponse(it.getString("tag"), it.getLong("count")) }
    }

    fun lockBookmark(connection: Connection, id: UUID): BookmarkResponse? = connection.lockBookmarkOn(id)

    fun findBookmark(connection: Connection, id: UUID): BookmarkResponse? = connection.findBookmarkOn(id)

    private fun Connection.lockBookmarkOn(id: UUID): BookmarkResponse? {
        val connection = this
        return query("select * from bookmarks where id = ? for update", listOf(id)) { connection.toBookmark(it) }.firstOrNull()
    }

    private fun Connection.findBookmarkOn(id: UUID): BookmarkResponse? {
        val connection = this
        return query("select * from bookmarks where id = ?", listOf(id)) { connection.toBookmark(it) }.firstOrNull()
    }

    private fun Connection.toBookmark(rs: ResultSet): BookmarkResponse {
        val id = rs.uuid("id")
        return BookmarkResponse(
            id = id,
            url = rs.getString("url"),
            title = rs.getString("title"),
            notes = rs.stringOrNull("notes"),
            tags = query("select tag from bookmark_tags where bookmark_id = ? order by tag", listOf(id)) { it.getString("tag") },
            visibility = rs.getString("visibility").wireValue(),
            status = rs.getString("status").wireValue(),
            owner = rs.getString("owner"),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.instant("updated_at"),
        )
    }

    private fun Connection.replaceTags(bookmarkId: UUID, tags: List<String>) {
        execute("delete from bookmark_tags where bookmark_id = ?", bookmarkId)
        tags.forEach { tag ->
            execute("insert into bookmark_tags (bookmark_id, tag) values (?, ?)", bookmarkId, tag)
        }
    }

    private fun bookmarkWhere(caller: String?, query: BookmarkListQuery): Clause {
        var scope = if (query.visibility == "public") {
            Clause("b.visibility = 'PUBLIC' and b.status = 'ACTIVE'")
        } else {
            val owner = caller ?: throw ApiProblem(HttpStatusCode.Unauthorized, "Unauthorized", detail = "Authentication is required.")
            var clause = Clause("b.owner = ?", owner)
            if (query.visibility != null) {
                clause = clause.and("b.visibility = ?", query.visibility.dbValue())
            }
            clause
        }
        query.tags.forEach { tag ->
            scope = scope.and("exists (select 1 from bookmark_tags bt where bt.bookmark_id = b.id and bt.tag = ?)", tag)
        }
        query.q?.takeIf { it.isNotBlank() }?.let {
            val pattern = "%${escapeLike(it.lowercase())}%"
            scope = scope.and("(lower(b.title) like ? escape '\\' or lower(coalesce(b.notes, '')) like ? escape '\\')", pattern, pattern)
        }
        return scope
    }

    private data class ValidatedBookmark(val url: String, val title: String, val notes: String?, val tags: List<String>, val visibility: String)

    private fun validateBookmark(request: BookmarkRequest): ValidatedBookmark {
        val validator = Validator()
        val url = request.url?.trim().orEmpty()
        if (url.isEmpty()) {
            validator.reject("url", "validation.url.required")
        } else {
            validator.check(url.length <= 2000 && isHttpUrl(url), "url", "validation.url.invalid")
        }
        val title = request.title?.trim().orEmpty()
        validator.check(title.isNotEmpty(), "title", "validation.title.required")
        validator.check(title.length <= 200, "title", "validation.title.too-long")
        validator.check((request.notes?.length ?: 0) <= 4000, "notes", "validation.notes.too-long")
        val tags = request.tags.orEmpty().map { it.trim().lowercase() }.toCollection(LinkedHashSet())
        validator.check(tags.size <= 10, "tags", "validation.tags.too-many")
        validator.check(tags.all { it.matches(TAG_PATTERN) }, "tags", "validation.tag.invalid")
        val visibility = request.visibility ?: "private"
        validator.check(visibility in VISIBILITIES, "visibility", "validation.visibility.invalid")
        validator.throwIfInvalid()
        return ValidatedBookmark(url, title, request.notes, tags.toList(), visibility)
    }
}

class MessageRepository(
    private val db: Database,
    private val audit: AuditRepository,
    private val mapper: ObjectMapper,
    private val logger: Logger,
) {
    suspend fun seed(language: String, entries: Map<String, String>): Int = db.transaction {
        val existing = query("select key from messages where language = ?", listOf(language)) { it.getString("key") }.toSet()
        val now = nowUtc()
        var inserted = 0
        entries.filterKeys { it !in existing }.forEach { (key, text) ->
            execute(
                """
                insert into messages (id, key, language, text, description, created_at, updated_at)
                values (?, ?, ?, ?, null, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(), key, language, text, now, now,
            )
            inserted++
        }
        inserted
    }

    suspend fun list(key: String?, language: String?, q: String?, page: Int, size: Int): PageResponse<MessageResponse> = db.read {
        var clause = Clause("1 = 1")
        key?.let { clause = clause.and("key = ?", it) }
        language?.let { clause = clause.and("language = ?", it) }
        q?.takeIf { it.isNotBlank() }?.let {
            val pattern = "%${escapeLike(it.lowercase())}%"
            clause = clause.and("(lower(key) like ? escape '\\' or lower(text) like ? escape '\\')", pattern, pattern)
        }
        val total = queryLong("select count(*) from messages where ${clause.sql}", clause.args)
        val items = query(
            """
            select * from messages
            where ${clause.sql}
            order by key, language
            limit ? offset ?
            """.trimIndent(),
            clause.args + listOf(size, page * size),
        ) { it.toMessage() }
        PageResponse(items, page, size, total, pages(total, size))
    }

    suspend fun get(id: UUID): MessageResponse = db.read {
        findMessage(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    suspend fun create(actor: String, request: MessageRequest): MessageResponse = db.transaction {
        val input = validate(request)
        if (exists(input.key, input.language)) duplicate(input)
        val now = nowUtc()
        val id = UUID.randomUUID()
        try {
            execute(
                """
                insert into messages (id, key, language, text, description, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, input.key, input.language, input.text, input.description, now, now,
            )
        } catch (error: SQLException) {
            if (error.sqlState == "23505") duplicate(input)
            throw error
        }
        val message = findMessage(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        audit.record(this, actor, "message.created", "message", id.toString(), snapshot(message))
        logMessageEvent("message_created", "Message created", actor, message)
        message
    }

    suspend fun update(actor: String, id: UUID, request: MessageRequest): MessageResponse = db.transaction {
        val current = findMessage(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        val input = validate(request)
        findByKeyLanguage(input.key, input.language)?.takeIf { it.id != id }?.let { duplicate(input) }
        try {
            execute(
                """
                update messages
                set key = ?, language = ?, text = ?, description = ?, updated_at = ?
                where id = ?
                """.trimIndent(),
                input.key, input.language, input.text, input.description, nowUtc(), id,
            )
        } catch (error: SQLException) {
            if (error.sqlState == "23505") duplicate(input)
            throw error
        }
        val message = findMessage(id) ?: current
        audit.record(this, actor, "message.updated", "message", id.toString(), snapshot(message))
        logMessageEvent("message_updated", "Message updated", actor, message)
        message
    }

    suspend fun delete(actor: String, id: UUID) = db.transaction {
        val message = findMessage(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        execute("delete from messages where id = ?", id)
        audit.record(this, actor, "message.deleted", "message", id.toString(), snapshot(message))
        logMessageEvent("message_deleted", "Message deleted", actor, message)
    }

    suspend fun bundle(language: String): Map<String, String> = db.read {
        val messages = query(
            """
            select * from messages
            where language in (?, ?)
            order by key, language
            """.trimIndent(),
            listOf(DEFAULT_LANGUAGE, language),
        ) { it.toMessage() }
        val result = linkedMapOf<String, String>()
        messages.forEach {
            if (it.language == language || it.key !in result) {
                result[it.key] = it.text
            }
        }
        result.toSortedMap()
    }

    suspend fun supportedLanguages(): Set<String> = db.read {
        query("select distinct language from messages", emptyList()) { it.getString("language") }.toSet()
    }

    suspend fun text(key: String, language: String): String? = db.read {
        query("select text from messages where key = ? and language = ?", listOf(key, language)) { it.getString("text") }.firstOrNull()
    }

    private fun Connection.exists(key: String, language: String): Boolean =
        queryLong("select count(*) from messages where key = ? and language = ?", listOf(key, language)) > 0

    private fun Connection.findByKeyLanguage(key: String, language: String): MessageResponse? =
        query("select * from messages where key = ? and language = ?", listOf(key, language)) { it.toMessage() }.firstOrNull()

    private fun Connection.findMessage(id: UUID): MessageResponse? =
        query("select * from messages where id = ?", listOf(id)) { it.toMessage() }.firstOrNull()

    private fun ResultSet.toMessage() = MessageResponse(
        id = uuid("id"),
        key = getString("key"),
        language = getString("language"),
        text = getString("text"),
        description = stringOrNull("description"),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )

    private data class ValidatedMessage(val key: String, val language: String, val text: String, val description: String?)

    private fun validate(request: MessageRequest): ValidatedMessage {
        val validator = Validator()
        val key = request.key?.trim().orEmpty()
        validator.check(key.matches(MESSAGE_KEY_PATTERN) && key.length <= 150, "key", "validation.message.key.invalid")
        val language = request.language?.trim().orEmpty()
        validator.check(language.matches(LANGUAGE_PATTERN), "language", "validation.message.language.invalid")
        val text = request.text.orEmpty()
        validator.check(text.isNotEmpty(), "text", "validation.message.text.required")
        validator.check(text.length <= 2000, "text", "validation.message.text.too-long")
        validator.check((request.description?.length ?: 0) <= 1000, "description", "validation.message.description.too-long")
        validator.throwIfInvalid()
        return ValidatedMessage(key, language, text, request.description)
    }

    private fun duplicate(input: ValidatedMessage): Nothing {
        throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "A message with key '${input.key}' and language '${input.language}' already exists.")
    }

    private fun snapshot(message: MessageResponse) = mapOf(
        "key" to message.key,
        "language" to message.language,
        "text" to message.text,
        "description" to message.description,
    )

    private fun logMessageEvent(event: String, message: String, actor: String, response: MessageResponse) {
        logger.logEvent(
            Level.INFO,
            event,
            "success",
            message,
            "actor" to actor,
            "resource_type" to "message",
            "resource_id" to response.id.toString(),
            "message_key" to response.key,
            "language" to response.language,
        )
    }
}

class ModerationRepository(
    private val db: Database,
    private val bookmarks: BookmarkRepository,
    private val audit: AuditRepository,
    private val logger: Logger,
) {
    suspend fun report(reporter: String, bookmarkId: UUID, request: ReportRequest): ReportResponse = db.transaction {
        val bookmark = bookmarks.findBookmark(this, bookmarkId) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (bookmark.visibility != "public" || bookmark.status != "active") throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        val reason = validateReportRequest(request)
        if (openReportExists(bookmarkId, reporter)) {
            throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "You already have an open report on this bookmark.")
        }
        val id = UUID.randomUUID()
        try {
            execute(
                """
                insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
                values (?, ?, ?, ?, ?, 'OPEN', ?)
                """.trimIndent(),
                id, bookmarkId, reporter, reason.dbValue(), request.comment, nowUtc(),
            )
        } catch (error: SQLException) {
            if (error.sqlState == "23505") {
                throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "You already have an open report on this bookmark.")
            }
            throw error
        }
        val report = findReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        logger.logEvent(
            Level.INFO,
            "report_created",
            "success",
            "Report created on a public bookmark",
            "actor" to reporter,
            "resource_type" to "report",
            "resource_id" to report.id.toString(),
            "bookmark_id" to bookmarkId.toString(),
            "reason" to report.reason,
        )
        report
    }

    suspend fun listAdmin(status: String, page: Int, size: Int): PageResponse<ReportResponse> = db.read {
        val total = queryLong("select count(*) from reports where status = ?", listOf(status.dbValue()))
        val items = query(
            """
            select * from reports
            where status = ?
            order by created_at asc, id asc
            limit ? offset ?
            """.trimIndent(),
            listOf(status.dbValue(), size, page * size),
        ) { it.toReport() }
        PageResponse(items, page, size, total, pages(total, size))
    }

    suspend fun listMine(reporter: String, status: String?, page: Int, size: Int): PageResponse<ReportResponse> = db.read {
        var clause = Clause("reporter = ?", reporter)
        status?.let { clause = clause.and("status = ?", it.dbValue()) }
        val total = queryLong("select count(*) from reports where ${clause.sql}", clause.args)
        val items = query(
            """
            select * from reports
            where ${clause.sql}
            order by created_at desc, id desc
            limit ? offset ?
            """.trimIndent(),
            clause.args + listOf(size, page * size),
        ) { it.toReport() }
        PageResponse(items, page, size, total, pages(total, size))
    }

    suspend fun updateMine(reporter: String, id: UUID, request: ReportRequest): ReportResponse = db.transaction {
        val report = lockReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (report.reporter != reporter) throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (report.status != "open") throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "The report has already been resolved.")
        val reason = validateReportRequest(request)
        execute("update reports set reason = ?, comment = ? where id = ?", reason.dbValue(), request.comment, id)
        val updated = findReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        logger.logEvent(
            Level.INFO,
            "report_updated",
            "success",
            "Report updated by its reporter",
            "actor" to reporter,
            "resource_type" to "report",
            "resource_id" to updated.id.toString(),
            "bookmark_id" to updated.bookmarkId.toString(),
            "reason" to updated.reason,
        )
        updated
    }

    suspend fun withdraw(reporter: String, id: UUID) = db.transaction {
        val report = lockReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (report.reporter != reporter) throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (report.status != "open") throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "The report has already been resolved.")
        execute("delete from reports where id = ?", id)
        logger.logEvent(
            Level.INFO,
            "report_withdrawn",
            "success",
            "Report withdrawn by its reporter",
            "actor" to reporter,
            "resource_type" to "report",
            "resource_id" to report.id.toString(),
            "bookmark_id" to report.bookmarkId.toString(),
        )
    }

    suspend fun resolve(actor: String, id: UUID, request: ReportResolutionRequest): ReportResponse = db.transaction {
        val resolution = validateResolution(request)
        if (resolution == "actioned") {
            val bookmarkId = query("select bookmark_id from reports where id = ?", listOf(id)) { it.uuid("bookmark_id") }.firstOrNull()
                ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
            bookmarks.lockBookmark(this, bookmarkId)
        }
        val report = lockReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (resolution == "open") {
            reopenOne(report, actor)
            return@transaction findReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        }
        resolveOne(report, resolution, actor, request.note, autoResolved = false)
        if (resolution == "actioned") {
            hideBookmark(actor, report.bookmarkId, request.note)
            query(
                """
                select * from reports
                where bookmark_id = ? and status = 'OPEN'
                order by id asc
                for update
                """.trimIndent(),
                listOf(report.bookmarkId),
            ) { it.toReport() }
                .filter { it.id != report.id }
                .forEach { resolveOne(it, "actioned", actor, request.note, autoResolved = true) }
        }
        findReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    suspend fun setBookmarkStatus(actor: String, id: UUID, request: BookmarkStatusRequest): BookmarkResponse = db.transaction {
        val status = request.status
        val validator = Validator()
        validator.check(status in BOOKMARK_STATUSES, "status", "validation.bookmark-status.invalid")
        validator.check((request.note?.length ?: 0) <= 1000, "note", "validation.bookmark-status.note.too-long")
        validator.throwIfInvalid()
        val bookmark = bookmarks.lockBookmark(this, id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        execute("update bookmarks set status = ?, updated_at = ? where id = ?", status!!.dbValue(), nowUtc(), id)
        audit.record(
            this,
            actor,
            "bookmark.status-changed",
            "bookmark",
            id.toString(),
            mapOf("from" to bookmark.status, "to" to status, "note" to request.note),
        )
        logger.logEvent(
            Level.INFO,
            "bookmark_status_changed",
            "success",
            "Bookmark moderation status changed",
            "actor" to actor,
            "resource_type" to "bookmark",
            "resource_id" to id.toString(),
            "from" to bookmark.status,
            "to" to status,
        )
        bookmarks.findBookmark(this, id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    private fun Connection.reopenOne(report: ReportResponse, actor: String) {
        if (queryLong(
                """
                select count(*) from reports
                where bookmark_id = ? and reporter = ? and status = 'OPEN' and id <> ?
                """.trimIndent(),
                listOf(report.bookmarkId, report.reporter, report.id),
            ) > 0
        ) {
            throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "The reporter already has another open report on this bookmark.")
        }
        try {
            execute(
                """
                update reports
                set status = 'OPEN', resolved_by = null, resolved_at = null, resolution_note = null
                where id = ?
                """.trimIndent(),
                report.id,
            )
        } catch (error: SQLException) {
            if (error.sqlState == "23505") {
                throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "The reporter already has another open report on this bookmark.")
            }
            throw error
        }
        audit.record(this, actor, "report.reopened", "report", report.id.toString(), mapOf("bookmarkId" to report.bookmarkId.toString()))
        logger.logEvent(
            Level.INFO,
            "report_reopened",
            "success",
            "Report re-opened",
            "actor" to actor,
            "resource_type" to "report",
            "resource_id" to report.id.toString(),
            "bookmark_id" to report.bookmarkId.toString(),
        )
    }

    private fun Connection.resolveOne(report: ReportResponse, resolution: String, actor: String, note: String?, autoResolved: Boolean) {
        val now = nowUtc()
        execute(
            """
            update reports
            set status = ?, resolved_by = ?, resolved_at = ?, resolution_note = ?
            where id = ?
            """.trimIndent(),
            resolution.dbValue(), actor, now, note, report.id,
        )
        audit.record(
            this,
            actor,
            "report.resolved",
            "report",
            report.id.toString(),
            mapOf("bookmarkId" to report.bookmarkId.toString(), "resolution" to resolution, "note" to note, "autoResolved" to autoResolved),
        )
        logger.logEvent(
            Level.INFO,
            "report_resolved",
            "success",
            "Report resolved",
            "actor" to actor,
            "resource_type" to "report",
            "resource_id" to report.id.toString(),
            "bookmark_id" to report.bookmarkId.toString(),
            "resolution" to resolution,
            "auto_resolved" to autoResolved,
        )
    }

    private fun Connection.hideBookmark(actor: String, bookmarkId: UUID, note: String?) {
        val bookmark = bookmarks.findBookmark(this, bookmarkId) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (bookmark.status == "hidden") return
        execute("update bookmarks set status = 'HIDDEN', updated_at = ? where id = ?", nowUtc(), bookmarkId)
        audit.record(
            this,
            actor,
            "bookmark.status-changed",
            "bookmark",
            bookmarkId.toString(),
            mapOf("from" to "active", "to" to "hidden", "note" to note),
        )
        logger.logEvent(
            Level.INFO,
            "bookmark_status_changed",
            "success",
            "Bookmark hidden by an actioned report",
            "actor" to actor,
            "resource_type" to "bookmark",
            "resource_id" to bookmarkId.toString(),
            "from" to "active",
            "to" to "hidden",
        )
    }

    private fun Connection.lockReport(id: UUID): ReportResponse? =
        query("select * from reports where id = ? for update", listOf(id)) { it.toReport() }.firstOrNull()

    private fun Connection.findReport(id: UUID): ReportResponse? =
        query("select * from reports where id = ?", listOf(id)) { it.toReport() }.firstOrNull()

    private fun Connection.openReportExists(bookmarkId: UUID, reporter: String): Boolean =
        queryLong("select count(*) from reports where bookmark_id = ? and reporter = ? and status = 'OPEN'", listOf(bookmarkId, reporter)) > 0

    private fun ResultSet.toReport() = ReportResponse(
        id = uuid("id"),
        bookmarkId = uuid("bookmark_id"),
        reporter = getString("reporter"),
        reason = getString("reason").wireValue(),
        comment = stringOrNull("comment"),
        status = getString("status").wireValue(),
        resolvedBy = stringOrNull("resolved_by"),
        resolvedAt = instantOrNull("resolved_at"),
        resolutionNote = stringOrNull("resolution_note"),
        createdAt = instant("created_at"),
    )

    private fun validateReportRequest(request: ReportRequest): String {
        val validator = Validator()
        val reason = request.reason
        validator.check(reason in REPORT_REASONS, "reason", "validation.report.reason.invalid")
        validator.check((request.comment?.length ?: 0) <= 1000, "comment", "validation.report.comment.too-long")
        validator.throwIfInvalid()
        return reason!!
    }

    private fun validateResolution(request: ReportResolutionRequest): String {
        val validator = Validator()
        val resolution = request.resolution
        validator.check(resolution in REPORT_STATUSES, "resolution", "validation.resolution.invalid")
        validator.check((request.note?.length ?: 0) <= 1000, "note", "validation.resolution.note.too-long")
        validator.throwIfInvalid()
        return resolution!!
    }
}

class AccountRepository(private val db: Database, private val audit: AuditRepository, private val logger: Logger) {
    suspend fun recordSeen(username: String): UserAccountResponse = db.transaction {
        val now = nowUtc()
        execute(
            """
            insert into user_accounts (username, first_seen, last_seen, status)
            values (?, ?, ?, 'ACTIVE')
            on conflict (username) do update set last_seen = excluded.last_seen
            """.trimIndent(),
            username, now, now,
        )
        findAccount(username) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    suspend fun list(q: String?, status: String?, page: Int, size: Int): PageResponse<UserAccountResponse> = db.read {
        var clause = Clause("1 = 1")
        q?.takeIf { it.isNotBlank() }?.let { clause = clause.and("lower(u.username) like ? escape '\\'", "%${escapeLike(it.lowercase())}%") }
        status?.let { clause = clause.and("u.status = ?", it.dbValue()) }
        val total = queryLong("select count(*) from user_accounts u where ${clause.sql}", clause.args)
        val items = query(
            """
            select u.*, (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count
            from user_accounts u
            where ${clause.sql}
            order by u.last_seen desc
            limit ? offset ?
            """.trimIndent(),
            clause.args + listOf(size, page * size),
        ) { it.toUserAccount() }
        PageResponse(items, page, size, total, pages(total, size))
    }

    suspend fun get(username: String): UserAccountResponse = db.read {
        findAccount(username) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    suspend fun setStatus(actor: String, username: String, request: UserStatusRequest): UserAccountResponse = db.transaction {
        val account = findAccount(username) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        val validator = Validator()
        validator.check(request.status in setOf("active", "blocked"), "status", "validation.user.status.invalid")
        if (request.status == "blocked") {
            validator.check(!request.reason.isNullOrBlank(), "reason", "validation.block.reason.required")
            validator.check((request.reason?.length ?: 0) <= 1000, "reason", "validation.block.reason.too-long")
        }
        validator.throwIfInvalid()
        if (request.status == "blocked" && username == actor) {
            throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "Admins cannot block themselves.")
        }
        if (request.status == "blocked") {
            execute("update user_accounts set status = 'BLOCKED', blocked_reason = ? where username = ?", request.reason, username)
            audit.record(this, actor, "user.blocked", "user", username, mapOf("reason" to request.reason))
            logger.logEvent(
                Level.INFO,
                "user_blocked",
                "success",
                "User account blocked",
                "actor" to actor,
                "resource_type" to "user",
                "resource_id" to username,
            )
        } else {
            execute("update user_accounts set status = 'ACTIVE', blocked_reason = null where username = ?", username)
            audit.record(this, actor, "user.unblocked", "user", username)
            logger.logEvent(
                Level.INFO,
                "user_unblocked",
                "success",
                "User account unblocked",
                "actor" to actor,
                "resource_type" to "user",
                "resource_id" to username,
            )
        }
        findAccount(username) ?: account
    }

    private fun Connection.findAccount(username: String): UserAccountResponse? =
        query(
            """
            select u.*, (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count
            from user_accounts u
            where u.username = ?
            """.trimIndent(),
            listOf(username),
        ) { it.toUserAccount() }.firstOrNull()

    private fun ResultSet.toUserAccount() = UserAccountResponse(
        username = getString("username"),
        firstSeen = instant("first_seen"),
        lastSeen = instant("last_seen"),
        status = getString("status").wireValue(),
        blockedReason = stringOrNull("blocked_reason"),
        bookmarkCount = getLong("bookmark_count"),
    )
}

class AuditRepository(private val db: Database, private val mapper: ObjectMapper) {
    fun record(connection: Connection, actor: String, action: String, targetType: String, targetId: String, detail: Map<String, Any?>? = null) {
        val json = detail?.let {
            PGobject().apply {
                type = "jsonb"
                value = mapper.writeValueAsString(it)
            }
        }
        connection.execute(
            """
            insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)
            values (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), actor, action, targetType, targetId, json, nowUtc(),
        )
    }

    suspend fun list(filter: AuditFilter, page: Int, size: Int): PageResponse<AuditEntryResponse> = db.read {
        var clause = Clause("1 = 1")
        filter.actor?.let { clause = clause.and("actor = ?", it) }
        filter.action?.let { clause = clause.and("action = ?", it) }
        filter.targetType?.let { clause = clause.and("target_type = ?", it) }
        filter.targetId?.let { clause = clause.and("target_id = ?", it) }
        filter.from?.let { clause = clause.and("created_at >= ?", it) }
        filter.to?.let { clause = clause.and("created_at <= ?", it) }
        val total = queryLong("select count(*) from audit_entries where ${clause.sql}", clause.args)
        val items = query(
            """
            select * from audit_entries
            where ${clause.sql}
            order by created_at desc
            limit ? offset ?
            """.trimIndent(),
            clause.args + listOf(size, page * size),
        ) { it.toAudit(mapper) }
        PageResponse(items, page, size, total, pages(total, size))
    }

    private fun ResultSet.toAudit(mapper: ObjectMapper): AuditEntryResponse {
        val detailJson = stringOrNull("detail")
        val detail = detailJson?.let { mapper.readValue(it, object : TypeReference<Map<String, Any?>>() {}) }
        return AuditEntryResponse(
            id = uuid("id"),
            actor = getString("actor"),
            action = getString("action"),
            targetType = getString("target_type"),
            targetId = getString("target_id"),
            detail = detail,
            createdAt = instant("created_at"),
        )
    }
}

class StatsRepository(private val db: Database) {
    suspend fun stats(): AdminStatsResponse = db.read {
        val today = LocalDate.now(ZoneOffset.UTC)
        val from = today.minusDays(29)
        val bookmarksByDay = countByDay("bookmarks", "created_at", from)
        val usersByDay = countByDay("user_accounts", "last_seen", from)
        AdminStatsResponse(
            totals = StatsTotals(
                users = queryLong("select count(*) from user_accounts"),
                bookmarks = queryLong("select count(*) from bookmarks"),
                publicBookmarks = queryLong("select count(*) from bookmarks where visibility = 'PUBLIC'"),
                hiddenBookmarks = queryLong("select count(*) from bookmarks where status = 'HIDDEN'"),
                openReports = queryLong("select count(*) from reports where status = 'OPEN'"),
            ),
            daily = (0 until 30).map { offset ->
                val date = from.plusDays(offset.toLong())
                DailyStat(date, bookmarksByDay[date] ?: 0, usersByDay[date] ?: 0)
            },
            topTags = query(
                """
                select tag, count(*) as count
                from bookmark_tags
                group by tag
                order by count(*) desc, tag
                limit 10
                """.trimIndent(),
            ) { TagCountResponse(it.getString("tag"), it.getLong("count")) },
        )
    }

    private fun Connection.countByDay(table: String, column: String, from: LocalDate): Map<LocalDate, Long> =
        query(
            """
            select ($column at time zone 'UTC')::date as day, count(*) as count
            from $table
            where $column >= ?
            group by day
            """.trimIndent(),
            listOf(from.atStartOfDay(ZoneOffset.UTC).toInstant()),
        ) { it.getObject("day", LocalDate::class.java) to it.getLong("count") }.toMap()
}

class MessageLocalizer(private val messages: MessageRepository) {
    suspend fun resolve(lang: String?, acceptLanguage: String?): String {
        val supported = messages.supportedLanguages()
        if (lang != null && lang in supported) return lang
        parseAcceptLanguage(acceptLanguage).forEach { language ->
            if (language in supported) return language
        }
        return DEFAULT_LANGUAGE
    }

    suspend fun localize(key: String, call: ApplicationCall): String {
        val language = resolve(call.request.queryParameters["lang"], call.request.headers[HttpHeaders.AcceptLanguage])
        return messages.text(key, language) ?: messages.text(key, DEFAULT_LANGUAGE) ?: key
    }

    private fun parseAcceptLanguage(header: String?): List<String> {
        if (header.isNullOrBlank()) return emptyList()
        return runCatching {
            Locale.LanguageRange.parse(header).mapNotNull { range ->
                Locale.forLanguageTag(range.range).language.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }
}

class JwtAuthenticator(private val config: Config, private val mapper: ObjectMapper, private val logger: Logger) {
    private val http = HttpClient.newBuilder().build()
    private val jwkSource: RemoteJWKSet<SecurityContext> by lazy {
        RemoteJWKSet(URI(resolveJwksUri()).toURL())
    }

    suspend fun authenticate(call: ApplicationCall): Identity? {
        val header = call.request.header(HttpHeaders.Authorization) ?: return null
        val raw = header.removePrefix("Bearer ").takeIf { header.startsWith("Bearer ") && it.isNotBlank() }
            ?: throwRejected()
        return try {
            validate(raw)
        } catch (_: Exception) {
            throwRejected()
        }
    }

    private fun validate(raw: String): Identity {
        val jwt = SignedJWT.parse(raw)
        val algorithm = jwt.header.algorithm
        if (algorithm !in setOf(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512)) {
            throw IllegalArgumentException("unsupported JWT algorithm")
        }
        val keys = jwkSource.get(
            JWKSelector(JWKMatcher.Builder().keyID(jwt.header.keyID).build()),
            null,
        )
        val rsaKey = keys.filterIsInstance<RSAKey>().firstOrNull() ?: throw IllegalArgumentException("unknown JWT key")
        if (!jwt.verify(RSASSAVerifier(rsaKey.toRSAPublicKey()))) {
            throw IllegalArgumentException("invalid JWT signature")
        }
        val claims = jwt.jwtClaimsSet
        val now = Date()
        if (claims.issuer != config.issuerUri) throw IllegalArgumentException("invalid issuer")
        if (AUDIENCE !in claims.audience.orEmpty()) throw IllegalArgumentException("invalid audience")
        if (claims.expirationTime == null || claims.expirationTime.before(Date(now.time - 30_000))) {
            throw IllegalArgumentException("expired token")
        }
        claims.notBeforeTime?.let {
            if (it.after(Date(now.time + 30_000))) throw IllegalArgumentException("token not valid yet")
        }
        val username = claims.getStringClaim("preferred_username")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("missing preferred_username")
        val roles = ((claims.getJSONObjectClaim("realm_access")?.get("roles") as? Collection<*>).orEmpty())
            .filterIsInstance<String>()
        return Identity(
            username = username,
            name = claims.getStringClaim("name"),
            email = claims.getStringClaim("email"),
            roles = roles,
        )
    }

    private fun throwRejected(): Nothing {
        logger.logEvent(Level.INFO, "jwt_validation_failed", "failure", "Rejected a bearer token", "error_code" to "invalid_token")
        throw ApiProblem(HttpStatusCode.Unauthorized, "Unauthorized", detail = "Missing or invalid bearer token.")
    }

    private fun resolveJwksUri(): String {
        if (config.jwksUri.isNotBlank()) return config.jwksUri
        val request = HttpRequest.newBuilder(URI.create("${config.issuerUri}/.well-known/openid-configuration")).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("OIDC discovery answered ${response.statusCode()}")
        }
        return mapper.readTree(response.body()).path("jwks_uri").asText().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OIDC discovery response did not include jwks_uri")
    }
}

private suspend fun ApplicationCall.respondProblem(context: AppContext, problem: Problem, status: HttpStatusCode) {
    response.status(status)
    respondText(context.mapper.writeValueAsString(problem), PROBLEM_JSON, status)
}

private suspend fun ApplicationCall.respondJsonWithEtag(context: AppContext, body: Any, cacheControl: Boolean) {
    val bytes = context.mapper.writeValueAsBytes(body)
    val etag = "\"" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)) + "\""
    if (cacheControl) response.headers.append(HttpHeaders.CacheControl, "no-cache")
    response.headers.append(HttpHeaders.ETag, etag)
    val matches = request.headers[HttpHeaders.IfNoneMatch]
        ?.split(',')
        ?.map { it.trim() }
        ?.contains(etag) == true
    if (matches) {
        respond(HttpStatusCode.NotModified)
    } else {
        respondBytes(bytes, ContentType.Application.Json, HttpStatusCode.OK)
    }
}

private fun ApplicationCall.identity(): Identity? =
    if (attributes.contains(IDENTITY_KEY)) attributes[IDENTITY_KEY] else null

private fun ApplicationCall.requireIdentity(): Identity =
    identity() ?: throw ApiProblem(HttpStatusCode.Unauthorized, "Unauthorized", detail = "Authentication is required.")

private fun ApplicationCall.requireRole(role: String, context: AppContext): Identity {
    val identity = requireIdentity()
    if (role !in identity.roles) {
        context.logger.logEvent(
            Level.INFO,
            "authz_denied",
            "denied",
            "Denied a request lacking the required role",
            "actor" to identity.username,
        )
        throw ApiProblem(HttpStatusCode.Forbidden, "Forbidden", detail = "You do not have the role required for this operation.")
    }
    return identity
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveBody(): T =
    try {
        receive<T>()
    } catch (_: Throwable) {
        throw ApiProblem(HttpStatusCode.BadRequest, "Bad Request", detail = "Invalid JSON request body.")
    }

private fun ApplicationCall.uuidPath(name: String): UUID =
    try {
        UUID.fromString(parameters[name].orEmpty())
    } catch (_: Exception) {
        throw ApiProblem(HttpStatusCode.BadRequest, "Bad Request", detail = "Invalid UUID.")
    }

private fun ApplicationCall.pageParam(): Int = intParam("page", 0).also {
    if (it < 0) throw ValidationProblem.of("page", "validation.page.invalid")
}

private fun ApplicationCall.sizeParam(): Int = intParam("size", 20).also {
    if (it !in 1..100) throw ValidationProblem.of("size", "validation.size.invalid")
}

private fun ApplicationCall.intParam(name: String, default: Int): Int =
    request.queryParameters[name]?.toIntOrNull() ?: if (request.queryParameters[name] == null) default else throw ValidationProblem.of(name, "validation.$name.invalid")

private fun ApplicationCall.optionalReportStatus(): String? =
    request.queryParameters["status"]?.also {
        if (it !in REPORT_STATUSES) throw ValidationProblem.of("status", "validation.report.status.invalid")
    }

private fun ApplicationCall.bookmarkQuery(): BookmarkListQuery {
    val tags = request.queryParameters.getAll("tag").orEmpty().map { it.trim().lowercase() }
    val validator = Validator()
    validator.check(tags.all { it.matches(TAG_PATTERN) }, "tag", "validation.tag.invalid")
    val q = request.queryParameters["q"]?.also { requireMaxLength(it, 200, "q") }
    val visibility = request.queryParameters["visibility"]?.also {
        validator.check(it in VISIBILITIES, "visibility", "validation.visibility.invalid")
    }
    validator.throwIfInvalid()
    return BookmarkListQuery(tags, q, visibility)
}

private fun requireMaxLength(value: String?, max: Int, field: String) {
    if ((value?.length ?: 0) > max) throw ValidationProblem.of(field, "validation.$field.too-long")
}

private fun parseInstantParam(value: String, field: String): Instant =
    runCatching { Instant.parse(value) }.getOrElse { throw ValidationProblem.of(field, "validation.$field.invalid") }

private data class Clause(val sql: String, val args: List<Any?> = emptyList()) {
    constructor(sql: String, vararg args: Any?) : this(sql, args.toList())

    fun and(part: String, vararg values: Any?): Clause =
        Clause("($sql) and ($part)", args + values.toList())
}

private fun Connection.queryLong(sql: String, args: List<Any?> = emptyList()): Long =
    query(sql, args) { it.getLong(1) }.first()

private fun <T> Connection.query(sql: String, args: List<Any?> = emptyList(), mapper: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(args)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(mapper(rs))
            }
        }
    }

private fun Connection.execute(sql: String, vararg args: Any?) {
    prepareStatement(sql).use { statement ->
        statement.bind(args.toList())
        statement.executeUpdate()
    }
}

private fun PreparedStatement.bind(args: List<Any?>) {
    args.forEachIndexed { index, value ->
        val parameter = index + 1
        when (value) {
            null -> setObject(parameter, null)
            is Instant -> setObject(parameter, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
            is UUID -> setObject(parameter, value)
            else -> setObject(parameter, value)
        }
    }
}

private fun ResultSet.uuid(name: String): UUID = getObject(name, UUID::class.java)

private fun ResultSet.instant(name: String): Instant = getObject(name, OffsetDateTime::class.java).toInstant()

private fun ResultSet.instantOrNull(name: String): Instant? =
    getObject(name, OffsetDateTime::class.java)?.toInstant()

private fun ResultSet.stringOrNull(name: String): String? = getString(name).let { if (wasNull()) null else it }

private fun String.dbValue(): String = uppercase(Locale.ROOT).replace('-', '_')

private fun String.wireValue(): String = lowercase(Locale.ROOT).replace('_', '-')

private fun escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private fun isHttpUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.isAbsolute && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
}

private fun nowUtc(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

private fun pages(total: Long, size: Int): Int = if (total == 0L) 0 else ceil(total.toDouble() / size).toInt()

private fun hikari(config: Config): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.dbUser
            password = config.dbPassword
            maximumPoolSize = 10
            minimumIdle = 1
            poolName = "stackverse-ktor"
        },
    )

private fun jsonMapper(): ObjectMapper = ObjectMapper()
    .registerModule(KotlinModule.Builder().build())
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)

private fun configureLogging(config: Config) {
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    context.reset()
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    root.level = when (config.logLevel.lowercase(Locale.ROOT)) {
        "error" -> LogbackLevel.ERROR
        "warn" -> LogbackLevel.WARN
        "debug" -> LogbackLevel.DEBUG
        else -> LogbackLevel.INFO
    }
    val appender = ConsoleAppender<ILoggingEvent>().apply {
        this.context = context
        encoder = if (config.logFormat.lowercase(Locale.ROOT) == "text") {
            PatternLayoutEncoder().apply {
                this.context = context
                pattern = "%d{yyyy-MM-dd'T'HH:mm:ss.SSSX,UTC} %-5level [%logger] %msg%n%ex"
                start()
            }
        } else {
            LogstashEncoder().apply {
                fieldNames.timestamp = "timestamp"
                start()
            }
        }
        start()
    }
    root.addAppender(appender)
}

private fun Logger.logEvent(level: Level, event: String, outcome: String, message: String, vararg fields: Pair<String, Any?>) {
    var builder = atLevel(level)
        .addKeyValue("event", event)
        .addKeyValue("outcome", outcome)
    fields.forEach { (key, value) ->
        if (value != null) builder = builder.addKeyValue(key, value)
    }
    builder.log(message)
}
