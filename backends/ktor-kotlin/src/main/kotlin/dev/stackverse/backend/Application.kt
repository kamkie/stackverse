package dev.stackverse.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.JacksonConverter
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

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

private class StackverseRequestPluginConfig {
    lateinit var context: AppContext
}

private val StackverseRequestPlugin = createApplicationPlugin(
    name = "StackverseRequestPlugin",
    createConfiguration = ::StackverseRequestPluginConfig,
) {
    val context = pluginConfig.context
    onCall { call ->
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
}

fun Application.stackverseModule(context: AppContext) {
    install(ContentNegotiation) {
        register(ContentType.Application.Json, JacksonConverter(context.mapper))
    }
    install(StatusPages) {
        exception<ValidationProblem> { call, cause ->
            context.logger.logEvent(Level.INFO, "input_validation_failed", "failure", "Input validation failed")
            val localized = context.localizer.localizeAll(cause.violations.map { it.messageKey }, call)
            call.respondProblem(
                context,
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

    install(StackverseRequestPlugin) {
        this.context = context
    }

    monitor.subscribe(ApplicationStarted) {
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
    monitor.subscribe(ApplicationStopped) {
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
                call.response.headers.append("Deprecation", DEPRECATION)
                call.response.headers.append("Sunset", SUNSET)
                call.response.headers.append(HttpHeaders.Link, SUCCESSOR_LINK)
                val page = call.pageParam()
                val size = call.sizeParam()
                val query = call.bookmarkQuery()
                val identity = call.identity()
                val result = context.bookmarks.listOffset(identity?.username, query, page, size)
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
