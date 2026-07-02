package dev.stackverse.backend.common

import dev.stackverse.backend.message.MessageLocalizer
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Maps application exceptions to RFC 9457 problem documents. Standard MVC failures
 * (malformed JSON, type mismatches, unknown paths, ...) are already rendered as
 * problem documents by [ResponseEntityExceptionHandler].
 */
@RestControllerAdvice
class ApiExceptionHandler(private val localizer: MessageLocalizer) : ResponseEntityExceptionHandler() {

    @ExceptionHandler(ApiProblem::class)
    fun handleApiProblem(exception: ApiProblem, request: HttpServletRequest): ProblemDetail {
        val problem = ProblemDetail.forStatus(exception.status)
        problem.title = exception.title
        problem.detail = exception.detailKey?.let { localizer.localize(it, request) } ?: exception.detail
        return problem
    }

    /** SPEC rules 5 + 11: field errors with a `validation.*` key and a localized message. */
    @ExceptionHandler(ValidationProblem::class)
    fun handleValidation(exception: ValidationProblem, request: HttpServletRequest): ProblemDetail {
        val problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)
        problem.title = "Bad Request"
        problem.detail = "Request validation failed."
        problem.setProperty(
            "errors",
            exception.violations.map {
                mapOf(
                    "field" to it.field,
                    "messageKey" to it.messageKey,
                    "message" to localizer.localize(it.messageKey, request),
                )
            },
        )
        return problem
    }

    /** Method-security denials (`@PreAuthorize`) surface here, not in the filter chain. */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(exception: AccessDeniedException): ProblemDetail {
        val problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN)
        problem.title = "Forbidden"
        problem.detail = "You do not have the role required for this operation."
        return problem
    }
}
