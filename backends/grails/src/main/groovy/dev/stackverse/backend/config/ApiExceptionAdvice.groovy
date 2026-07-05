package dev.stackverse.backend.config

import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.JsonSupport
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class ApiExceptionAdvice {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionAdvice)

    @ExceptionHandler(ApiError)
    void api(ApiError error, HttpServletResponse response) {
        JsonSupport.writeProblem(response, error)
    }

    @ExceptionHandler(DuplicateKeyException)
    void duplicate(DuplicateKeyException error, HttpServletResponse response) {
        JsonSupport.writeProblem(response, ApiError.conflict())
    }

    @ExceptionHandler(NoHandlerFoundException)
    void notFound(NoHandlerFoundException error, HttpServletResponse response) {
        JsonSupport.writeProblem(response, ApiError.notFound())
    }

    @ExceptionHandler(Exception)
    void unexpected(Exception error, HttpServletResponse response) {
        log.error("Unhandled request failure", error)
        JsonSupport.writeProblem(response, new ApiError(500, "An unexpected server error occurred.", "Internal Server Error"))
    }
}
