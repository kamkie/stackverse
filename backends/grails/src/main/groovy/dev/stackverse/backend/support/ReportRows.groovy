package dev.stackverse.backend.support

class ReportRows {
    static Map row(rs) {
        [
            id            : SqlRows.uuid(rs, "id").toString(),
            bookmarkId    : SqlRows.uuid(rs, "bookmark_id").toString(),
            reporter      : rs.getString("reporter"),
            reason        : rs.getString("reason"),
            comment       : rs.getString("comment"),
            status        : rs.getString("status"),
            resolvedBy    : rs.getString("resolved_by"),
            resolvedAt    : SqlRows.rfc3339(SqlRows.instant(rs, "resolved_at")),
            resolutionNote: rs.getString("resolution_note"),
            createdAt     : SqlRows.rfc3339(SqlRows.instant(rs, "created_at"))
        ]
    }
}
