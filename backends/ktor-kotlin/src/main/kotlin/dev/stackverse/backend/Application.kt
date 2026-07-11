package dev.stackverse.backend

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.JacksonConverter
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

fun main() {
    val config = Config.load()
    configureLogging(config)
    val mapper = jsonMapper()
    val logger = LoggerFactory.getLogger("dev.stackverse.backend.Application")

    try {
        embeddedServer(Netty, port = config.port) {
            configureStackverseDependencies(config, mapper, logger)
            initializeStackverseData()
            stackverseModule()
        }.start(wait = true)
    } catch (error: Throwable) {
        logger.atLevel(Level.ERROR)
            .addKeyValue("event", "application_start")
            .addKeyValue("outcome", "failure")
            .addKeyValue("error_code", "startup_failed")
            .log("Application refused to start", error)
        throw error
    }
}

private class StackverseRequestPluginConfig {
    lateinit var jwtAuthenticator: JwtAuthenticator
    lateinit var accounts: AccountRepository
    lateinit var logger: Logger
}

private val StackverseRequestPlugin = createApplicationPlugin(
    name = "StackverseRequestPlugin",
    createConfiguration = ::StackverseRequestPluginConfig,
) {
    val jwtAuthenticator = pluginConfig.jwtAuthenticator
    val accounts = pluginConfig.accounts
    val logger = pluginConfig.logger
    onCall { call ->
        call.appendDeprecatedBookmarkListHeaders()
        val identity = jwtAuthenticator.authenticate(call)
        if (identity != null) {
            val account = accounts.recordSeen(identity.username)
            if (account.status == "blocked") {
                logger.logEvent(
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
}

private fun ApplicationCall.appendDeprecatedBookmarkListHeaders() {
    if (request.httpMethod == HttpMethod.Get && request.path() == "/api/v1/bookmarks") {
        response.headers.append("Deprecation", DEPRECATION)
        response.headers.append("Sunset", SUNSET)
        response.headers.append(HttpHeaders.Link, SUCCESSOR_LINK)
    }
}

fun Application.stackverseModule() {
    val config: Config by dependencies
    val mapper: ObjectMapper by dependencies
    val logger: Logger by dependencies
    val database: Database by dependencies
    val audit: AuditRepository by dependencies
    val bookmarks: BookmarkRepository by dependencies
    val messages: MessageRepository by dependencies
    val accounts: AccountRepository by dependencies
    val moderation: ModerationRepository by dependencies
    val stats: StatsRepository by dependencies
    val localizer: MessageLocalizer by dependencies
    val jwtAuthenticator: JwtAuthenticator by dependencies

    install(ContentNegotiation) {
        register(ContentType.Application.Json, JacksonConverter(mapper))
    }
    install(StatusPages) {
        exception<ValidationProblem> { call, cause ->
            logger.logEvent(Level.INFO, "input_validation_failed", "failure", "Input validation failed")
            val localized = localizer.localizeAll(cause.violations.map { it.messageKey }, call)
            call.respondProblem(
                mapper,
                Problem(
                    title = "Bad Request",
                    status = 400,
                    errors = cause.violations.map {
                        FieldError(it.field, it.messageKey, localized[it.messageKey] ?: it.messageKey)
                    },
                ),
                HttpStatusCode.BadRequest,
            )
        }
        exception<ApiProblem> { call, cause ->
            call.respondProblem(
                mapper,
                Problem(
                    title = cause.title,
                    status = cause.status.value,
                    detail = cause.detailKey?.let { localizer.localize(it, call) } ?: cause.detail,
                ),
                cause.status,
            )
        }
        exception<BadRequestException> { call, _ ->
            call.respondProblem(mapper, Problem("Bad Request", 400, detail = "Invalid request."), HttpStatusCode.BadRequest)
        }
        exception<Throwable> { call, cause ->
            logger.atLevel(Level.ERROR)
                .addKeyValue("event", "request_failed")
                .addKeyValue("outcome", "failure")
                .addKeyValue("error_code", cause.javaClass.simpleName)
                .log("Unhandled request failure", cause)
            call.respondProblem(mapper, Problem("Internal Server Error", 500), HttpStatusCode.InternalServerError)
        }
    }

    install(StackverseRequestPlugin) {
        this.jwtAuthenticator = jwtAuthenticator
        this.accounts = accounts
        this.logger = logger
    }

    monitor.subscribe(ApplicationStarted) {
        logger.logEvent(
            Level.INFO,
            "application_start",
            "success",
            "Application listening",
            "port" to config.port,
            "db_host" to config.dbHost,
            "db_port" to config.dbPort,
            "db_name" to config.dbName,
            "oidc_issuer_uri" to config.issuerUri,
            "oidc_jwks_uri_set" to config.jwksUri.isNotBlank(),
            "log_format" to config.logFormat,
            "log_level" to config.logLevel,
        )
    }
    monitor.subscribe(ApplicationStopped) {
        logger.logEvent(Level.INFO, "application_stop", "success", "Application stopped")
    }

    routing {
        get("/healthz") {
            call.respondText("ok")
        }
        get("/readyz") {
            if (database.ready()) {
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
            call.respond(TagListResponse(bookmarks.tags(identity.username)))
        }

        route("/api/v1/bookmarks") {
            get {
                val page = call.pageParam()
                val size = call.sizeParam()
                val query = call.bookmarkQuery()
                val identity = call.identity()
                val result = bookmarks.listOffset(identity?.username, query, page, size)
                call.respond(result)
            }
            post {
                val identity = call.requireIdentity()
                val bookmark = bookmarks.create(identity.username, call.receiveBody())
                call.response.headers.append(HttpHeaders.Location, "/api/v1/bookmarks/${bookmark.id}")
                call.respond(HttpStatusCode.Created, bookmark)
            }
            get("{id}") {
                call.respond(bookmarks.get(call.identity()?.username, call.uuidPath("id")))
            }
            put("{id}") {
                val identity = call.requireIdentity()
                call.respond(bookmarks.update(identity.username, call.uuidPath("id"), call.receiveBody()))
            }
            delete("{id}") {
                val identity = call.requireIdentity()
                bookmarks.delete(identity.username, call.uuidPath("id"))
                call.respond(HttpStatusCode.NoContent)
            }
            post("{id}/reports") {
                val identity = call.requireIdentity()
                val report = moderation.report(identity.username, call.uuidPath("id"), call.receiveBody())
                call.respond(HttpStatusCode.Created, report)
            }
        }

        get("/api/v2/bookmarks") {
            val size = call.sizeParam()
            val query = call.bookmarkQuery()
            val cursor = call.request.queryParameters["cursor"]?.let { BookmarkCursor.decode(it) }
            val slice = bookmarks.listKeyset(call.identity()?.username, query, cursor, size)
            call.respond(slice)
        }

        route("/api/v1/reports") {
            get {
                val identity = call.requireIdentity()
                val page = call.pageParam()
                val size = call.sizeParam()
                val status = call.optionalReportStatus()
                call.respond(moderation.listMine(identity.username, status, page, size))
            }
            put("{id}") {
                val identity = call.requireIdentity()
                call.respond(moderation.updateMine(identity.username, call.uuidPath("id"), call.receiveBody()))
            }
            delete("{id}") {
                val identity = call.requireIdentity()
                moderation.withdraw(identity.username, call.uuidPath("id"))
                call.respond(HttpStatusCode.NoContent)
            }
        }

        route("/api/v1/admin/reports") {
            get {
                call.requireRole("moderator", logger)
                val page = call.pageParam()
                val size = call.sizeParam()
                val status = call.optionalReportStatus() ?: "open"
                call.respond(moderation.listAdmin(status, page, size))
            }
            put("{id}") {
                val identity = call.requireRole("moderator", logger)
                call.respond(moderation.resolve(identity.username, call.uuidPath("id"), call.receiveBody()))
            }
        }

        put("/api/v1/admin/bookmarks/{id}/status") {
            val identity = call.requireRole("moderator", logger)
            call.respond(moderation.setBookmarkStatus(identity.username, call.uuidPath("id"), call.receiveBody()))
        }

        route("/api/v1/admin/users") {
            get {
                call.requireRole("admin", logger)
                val page = call.pageParam()
                val size = call.sizeParam()
                val q = call.request.queryParameters["q"]?.also { requireMaxLength(it, 100, "q") }
                val status = call.request.queryParameters["status"]?.let {
                    if (it !in setOf("active", "blocked")) throw ValidationProblem.of("status", "validation.user.status.invalid")
                    it
                }
                call.respond(accounts.list(q, status, page, size))
            }
            get("{username}") {
                call.requireRole("admin", logger)
                call.respond(accounts.get(call.parameters["username"].orEmpty()))
            }
            put("{username}/status") {
                val identity = call.requireRole("admin", logger)
                call.respond(accounts.setStatus(identity.username, call.parameters["username"].orEmpty(), call.receiveBody()))
            }
        }

        get("/api/v1/admin/audit-log") {
            call.requireRole("admin", logger)
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
            call.respond(audit.list(filter, page, size))
        }

        get("/api/v1/admin/stats") {
            call.requireRole("moderator", logger)
            call.respondJsonWithEtag(mapper, stats.stats(), cacheControl = true)
        }

        route("/api/v1/messages") {
            get {
                val page = call.pageParam()
                val size = call.sizeParam()
                val q = call.request.queryParameters["q"]?.also { requireMaxLength(it, 200, "q") }
                val response = messages.list(
                    key = call.request.queryParameters["key"],
                    language = call.request.queryParameters["language"],
                    q = q,
                    page = page,
                    size = size,
                )
                call.respondJsonWithEtag(mapper, response, cacheControl = true)
            }
            post {
                val identity = call.requireRole("admin", logger)
                val message = messages.create(identity.username, call.receiveBody())
                call.response.headers.append(HttpHeaders.Location, "/api/v1/messages/${message.id}")
                call.respond(HttpStatusCode.Created, message)
            }
            get("bundle") {
                val language = localizer.resolve(
                    call.request.queryParameters["lang"],
                    call.request.headers[HttpHeaders.AcceptLanguage],
                )
                call.response.headers.append(HttpHeaders.ContentLanguage, language)
                call.respondJsonWithEtag(mapper, MessageBundleResponse(language, messages.bundle(language)), cacheControl = true)
            }
            get("{id}") {
                call.respondJsonWithEtag(mapper, messages.get(call.uuidPath("id")), cacheControl = true)
            }
            put("{id}") {
                val identity = call.requireRole("admin", logger)
                call.respond(messages.update(identity.username, call.uuidPath("id"), call.receiveBody()))
            }
            delete("{id}") {
                val identity = call.requireRole("admin", logger)
                messages.delete(identity.username, call.uuidPath("id"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
