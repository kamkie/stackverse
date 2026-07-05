package dev.stackverse.backend.support

class ApiError extends RuntimeException {
    final int status
    final String title
    final List<Map<String, Object>> errors

    ApiError(int status, String detail, String title = null, List<Map<String, Object>> errors = null) {
        super(detail)
        this.status = status
        this.title = title ?: titles[status] ?: "Error"
        this.errors = errors
    }

    static ApiError badRequest(String detail = "Validation failed.", List<Map<String, Object>> errors = null) {
        new ApiError(400, detail, "Bad Request", errors)
    }

    static ApiError unauthorized(String detail = "Authentication is required.") {
        new ApiError(401, detail, "Unauthorized")
    }

    static ApiError forbidden(String detail = "You do not have the role required for this operation.") {
        new ApiError(403, detail, "Forbidden")
    }

    static ApiError notFound(String detail = "Resource not found.") {
        new ApiError(404, detail, "Not Found")
    }

    static ApiError conflict(String detail = "The requested operation conflicts with the current resource state.") {
        new ApiError(409, detail, "Conflict")
    }

    private static final Map<Integer, String> titles = [
        400: "Bad Request",
        401: "Unauthorized",
        403: "Forbidden",
        404: "Not Found",
        409: "Conflict",
        503: "Service Unavailable"
    ]
}
