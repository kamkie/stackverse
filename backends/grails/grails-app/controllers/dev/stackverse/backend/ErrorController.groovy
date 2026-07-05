package dev.stackverse.backend

import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.ControllerSupport
import org.springframework.dao.DuplicateKeyException

class ErrorController implements ControllerSupport {
    def notFound() {
        problem(ApiError.notFound())
    }

    def serverError() {
        Throwable error = request.exception
        ApiError apiError = findCause(error, ApiError) as ApiError
        if (apiError) {
            problem(apiError)
            return
        }
        if (findCause(error, DuplicateKeyException)) {
            problem(ApiError.conflict())
            return
        }
        problem(new ApiError(500, "An unexpected server error occurred.", "Internal Server Error"))
    }

    private static Throwable findCause(Throwable error, Class type) {
        Throwable current = error
        while (current) {
            if (type.isInstance(current)) {
                return current
            }
            current = current.cause
        }
        null
    }
}
