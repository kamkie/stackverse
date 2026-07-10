package dev.stackverse.backend;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedMap;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
final class RequestParameters {
    int pagingPage(RequestContext request) {
        int page = intParam(request, "page", 0);
        if (page < 0) {
            throw StackverseProblem.badRequest("page must not be negative");
        }
        return page;
    }

    int pageSize(RequestContext request) {
        int size = intParam(request, "size", 20);
        if (size < 1 || size > 100) {
            throw StackverseProblem.badRequest("size must be between 1 and 100");
        }
        return size;
    }

    long offset(int page, int size) {
        return Math.multiplyFull(page, size);
    }

    void equalFilter(RequestContext request, SqlWhere where, String column, String parameter) {
        String value = singleParam(request, parameter);
        if (value != null) {
            where.and(column + " = ?", value);
        }
    }

    Instant timeParam(RequestContext request, String name) {
        String value = singleParam(request, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw StackverseProblem.badRequest(name + " must be an RFC 3339 timestamp");
        }
    }

    String singleParam(RequestContext request, String name) {
        List<String> values = queryParams(request).get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw StackverseProblem.badRequest(name + " must not be repeated");
        }
        return values.get(0);
    }

    MultivaluedMap<String, String> queryParams(RequestContext request) {
        return request.uriInfo().getQueryParameters();
    }

    void maxLength(String value, int max, String field) {
        if (value != null && length(value) > max) {
            throw StackverseProblem.badRequest(field + " must be at most " + max + " characters");
        }
    }

    UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException error) {
            throw StackverseProblem.notFound();
        }
    }

    String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private int intParam(RequestContext request, String name, int fallback) {
        String value = singleParam(request, name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw StackverseProblem.badRequest(name + " must be an integer");
        }
    }

    private static int length(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }
}
