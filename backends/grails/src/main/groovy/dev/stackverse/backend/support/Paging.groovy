package dev.stackverse.backend.support

class Paging {
    static Map resultPage(List items, int page, int size, Long total) {
        [
            items     : items,
            page      : page,
            size      : size,
            totalItems: total,
            totalPages: total == 0 ? 0 : Math.ceil(total / (double) size) as int
        ]
    }

    static int size(Object value, int defaultValue = 20) {
        int parsed = positiveInt(value, defaultValue, "size")
        if (parsed > 100) {
            throw ApiError.badRequest("Validation failed.", [[field: "size", messageKey: "validation.size.invalid", message: "Size must be between 1 and 100."]])
        }
        return parsed
    }

    static int page(Object value) {
        int parsed = positiveOrZero(value, 0, "page")
        return parsed
    }

    private static int positiveInt(Object value, int defaultValue, String field) {
        int parsed = parse(value, defaultValue, field)
        if (parsed < 1) {
            throw ApiError.badRequest("Validation failed.", [[field: field, messageKey: "validation.${field}.invalid", message: "${field} is invalid."]])
        }
        parsed
    }

    private static int positiveOrZero(Object value, int defaultValue, String field) {
        int parsed = parse(value, defaultValue, field)
        if (parsed < 0) {
            throw ApiError.badRequest("Validation failed.", [[field: field, messageKey: "validation.${field}.invalid", message: "${field} is invalid."]])
        }
        parsed
    }

    private static int parse(Object value, int defaultValue, String field) {
        if (value == null || value == "") {
            return defaultValue
        }
        try {
            return value.toString().toInteger()
        } catch (Exception ignored) {
            throw ApiError.badRequest("Validation failed.", [[field: field, messageKey: "validation.${field}.invalid", message: "${field} is invalid."]])
        }
    }
}
