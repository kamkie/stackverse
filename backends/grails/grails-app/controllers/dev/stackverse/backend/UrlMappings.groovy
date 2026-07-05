package dev.stackverse.backend

class UrlMappings {
    static mappings = {
        "/healthz"(controller: "meta", action: "healthz", method: "GET")
        "/readyz"(controller: "meta", action: "readyz", method: "GET")

        "/api/v1/me"(controller: "me", action: "show", method: "GET")

        "/api/v1/bookmarks"(controller: "bookmark", action: "listV1", method: "GET")
        "/api/v1/bookmarks"(controller: "bookmark", action: "create", method: "POST")
        "/api/v2/bookmarks"(controller: "bookmark", action: "listV2", method: "GET")
        "/api/v1/bookmarks/$id"(controller: "bookmark", action: "show", method: "GET")
        "/api/v1/bookmarks/$id"(controller: "bookmark", action: "update", method: "PUT")
        "/api/v1/bookmarks/$id"(controller: "bookmark", action: "delete", method: "DELETE")
        "/api/v1/bookmarks/$id/reports"(controller: "bookmark", action: "report", method: "POST")
        "/api/v1/tags"(controller: "tag", action: "list", method: "GET")

        "/api/v1/reports"(controller: "report", action: "listMine", method: "GET")
        "/api/v1/reports/$id"(controller: "report", action: "updateMine", method: "PUT")
        "/api/v1/reports/$id"(controller: "report", action: "withdraw", method: "DELETE")

        "/api/v1/admin/reports"(controller: "adminReport", action: "list", method: "GET")
        "/api/v1/admin/reports/$id"(controller: "adminReport", action: "resolve", method: "PUT")
        "/api/v1/admin/bookmarks/$id/status"(controller: "adminBookmark", action: "setStatus", method: "PUT")

        "/api/v1/admin/users"(controller: "adminUser", action: "list", method: "GET")
        "/api/v1/admin/users/$username"(controller: "adminUser", action: "show", method: "GET")
        "/api/v1/admin/users/$username/status"(controller: "adminUser", action: "setStatus", method: "PUT")
        "/api/v1/admin/audit-log"(controller: "adminAudit", action: "list", method: "GET")
        "/api/v1/admin/stats"(controller: "adminStats", action: "show", method: "GET")

        "/api/v1/messages"(controller: "message", action: "list", method: "GET")
        "/api/v1/messages"(controller: "message", action: "create", method: "POST")
        "/api/v1/messages/bundle"(controller: "message", action: "bundle", method: "GET")
        "/api/v1/messages/$id"(controller: "message", action: "show", method: "GET")
        "/api/v1/messages/$id"(controller: "message", action: "update", method: "PUT")
        "/api/v1/messages/$id"(controller: "message", action: "delete", method: "DELETE")

        "404"(controller: "error", action: "notFound")
        "500"(controller: "error", action: "serverError")
    }
}
