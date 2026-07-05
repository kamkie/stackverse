package dev.stackverse.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import org.slf4j.event.Level
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

suspend fun ApplicationCall.respondProblem(context: AppContext, problem: Problem, status: HttpStatusCode) {
    response.status(status)
    respondText(context.mapper.writeValueAsString(problem), PROBLEM_JSON, status)
}

suspend fun ApplicationCall.respondJsonWithEtag(context: AppContext, body: Any, cacheControl: Boolean) {
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

fun ApplicationCall.identity(): Identity? =
    if (attributes.contains(IDENTITY_KEY)) attributes[IDENTITY_KEY] else null

fun ApplicationCall.requireIdentity(): Identity =
    identity() ?: throw ApiProblem(HttpStatusCode.Unauthorized, "Unauthorized", detail = "Authentication is required.")

fun ApplicationCall.requireRole(role: String, context: AppContext): Identity {
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

suspend inline fun <reified T : Any> ApplicationCall.receiveBody(): T =
    try {
        receive<T>()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw ApiProblem(HttpStatusCode.BadRequest, "Bad Request", detail = "Invalid JSON request body.")
    }

fun ApplicationCall.uuidPath(name: String): UUID =
    try {
        UUID.fromString(parameters[name].orEmpty())
    } catch (_: Exception) {
        throw ApiProblem(HttpStatusCode.BadRequest, "Bad Request", detail = "Invalid UUID.")
    }

fun ApplicationCall.pageParam(): Int = intParam("page", 0).also {
    if (it < 0) throw ValidationProblem.of("page", "validation.page.invalid")
}

fun ApplicationCall.sizeParam(): Int = intParam("size", 20).also {
    if (it !in 1..100) throw ValidationProblem.of("size", "validation.size.invalid")
}

fun ApplicationCall.intParam(name: String, default: Int): Int =
    request.queryParameters[name]?.toIntOrNull() ?: if (request.queryParameters[name] == null) default else throw ValidationProblem.of(name, "validation.$name.invalid")

fun ApplicationCall.optionalReportStatus(): String? =
    request.queryParameters["status"]?.also {
        if (it !in REPORT_STATUSES) throw ValidationProblem.of("status", "validation.report.status.invalid")
    }

fun ApplicationCall.bookmarkQuery(): BookmarkListQuery {
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

fun requireMaxLength(value: String?, max: Int, field: String) {
    if ((value?.length ?: 0) > max) throw ValidationProblem.of(field, "validation.$field.too-long")
}

fun parseInstantParam(value: String, field: String): Instant =
    runCatching { Instant.parse(value) }.getOrElse { throw ValidationProblem.of(field, "validation.$field.invalid") }
