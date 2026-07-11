package dev.stackverse.backend.common

import org.springframework.http.HttpStatus

/**
 * Application exceptions that map 1:1 onto RFC 9457 problem documents.
 * Thrown from services, translated in [ApiExceptionHandler].
 */
sealed class ApiProblem(
    val status: HttpStatus,
    val title: String,
    /** Optional key into the messages table; resolved to a localized `detail`. */
    val detailKey: String? = null,
    val detail: String? = null,
) : RuntimeException(detail ?: title)

/** Resource missing — or deliberately masked (rule 1: existence is not disclosed). */
class NotFoundProblem : ApiProblem(HttpStatus.NOT_FOUND, "Not Found")

class ConflictProblem(detail: String, detailKey: String? = null) : ApiProblem(HttpStatus.CONFLICT, "Conflict", detailKey = detailKey, detail = detail)

/** Anonymous caller on an endpoint that needs authentication (e.g. non-public listing). */
class UnauthorizedProblem : ApiProblem(HttpStatus.UNAUTHORIZED, "Unauthorized")

class BadRequestProblem(detail: String) : ApiProblem(HttpStatus.BAD_REQUEST, "Bad Request", detail = detail)

/** One field-level validation failure; `message` gets localized when the problem is rendered. */
data class FieldViolation(val field: String, val messageKey: String)

/** Validation failure carrying field-level errors (rule 5 + 11). */
class ValidationProblem(val violations: List<FieldViolation>) : RuntimeException("Validation failed")

/** Collects violations and throws once at the end, so all field errors are reported together. */
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
