package dev.stackverse.backend

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.Base64
import java.util.UUID

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

class ApiProblem(
    val status: HttpStatusCode,
    val title: String,
    val detail: String? = null,
    val detailKey: String? = null,
) : RuntimeException(detail ?: title)

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
