package dev.stackverse.backend.audit

import dev.stackverse.backend.support.Paging
import dev.stackverse.backend.support.SqlRows
import dev.stackverse.backend.support.TimeSource
import groovy.json.JsonOutput
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

import java.sql.Timestamp

@Service
class AuditService {
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired TimeSource timeSource

    @Transactional
    void record(String actor, String action, String targetType, String targetId, Map detail = null) {
        jdbcTemplate.update("""
            insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)
            values (?, ?, ?, ?, ?, ?::jsonb, ?)
        """,
            UUID.randomUUID(), actor, action, targetType, targetId,
            detail == null ? null : JsonOutput.toJson(detail),
            Timestamp.from(timeSource.now())
        )
    }

    Map list(Map params) {
        int page = Paging.page(params.page)
        int size = Paging.size(params.size)
        List clauses = []
        List args = []
        ["actor", "action"].each { field ->
            if (params[field]) {
                clauses << "${field} = ?"
                args << params[field].toString()
            }
        }
        if (params.targetType) {
            clauses << "target_type = ?"
            args << params.targetType.toString()
        }
        if (params.targetId) {
            clauses << "target_id = ?"
            args << params.targetId.toString()
        }
        if (params.from) {
            clauses << "created_at >= ?"
            args << Timestamp.from(java.time.Instant.parse(params.from.toString()))
        }
        if (params.to) {
            clauses << "created_at <= ?"
            args << Timestamp.from(java.time.Instant.parse(params.to.toString()))
        }
        String where = clauses ? "where ${clauses.join(' and ')}" : ""
        Long total = jdbcTemplate.queryForObject("select count(*) from audit_entries ${where}", Long, args as Object[])
        List pageArgs = args + [size, page * size]
        List items = jdbcTemplate.query("""
            select id, actor, action, target_type, target_id, detail, created_at
            from audit_entries
            ${where}
            order by created_at desc, id desc
            limit ? offset ?
        """, { rs, rowNum ->
            [
                id        : SqlRows.uuid(rs, "id").toString(),
                actor     : rs.getString("actor"),
                action    : rs.getString("action"),
                targetType: rs.getString("target_type"),
                targetId  : rs.getString("target_id"),
                detail    : rs.getString("detail") ? new groovy.json.JsonSlurper().parseText(rs.getString("detail")) : null,
                createdAt : SqlRows.rfc3339(SqlRows.instant(rs, "created_at"))
            ]
        }, pageArgs as Object[])
        [
            items     : items,
            page      : page,
            size      : size,
            totalItems: total,
            totalPages: total == 0 ? 0 : Math.ceil(total / (double) size) as int
        ]
    }
}
