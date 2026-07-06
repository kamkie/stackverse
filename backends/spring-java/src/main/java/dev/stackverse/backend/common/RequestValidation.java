package dev.stackverse.backend.common;

public final class RequestValidation {
    private RequestValidation() {
    }

    /** Shared bounds for page/size query parameters (spec: size 1-100, default 20). */
    public static void requireValidPaging(int page, int size) {
        if (page < 0) {
            throw new BadRequestProblem("page must not be negative");
        }
        if (size < 1 || size > 100) {
            throw new BadRequestProblem("size must be between 1 and 100");
        }
    }

    public static void requireMaxLength(String value, int max, String name) {
        if (value != null && value.length() > max) {
            throw new BadRequestProblem(name + " must be at most " + max + " characters");
        }
    }
}
