package dev.stackverse.backend;

import static dev.stackverse.backend.HttpResponses.pageResponse;
import static dev.stackverse.backend.PersistenceSupport.instant;
import static dev.stackverse.backend.PersistenceSupport.params;
import static dev.stackverse.backend.PersistenceSupport.query;
import static dev.stackverse.backend.PersistenceSupport.queryOne;
import static dev.stackverse.backend.PersistenceSupport.scalarLong;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AdminService {
    private static final Logger LOG = Logger.getLogger(AdminService.class);

    private final DatabaseOperations database;
    private final Authorization authorization;
    private final RequestParameters requestParameters;
    private final HttpResponses httpResponses;
    private final AuditTrail auditTrail;
    private final ObjectMapper mapper;

    public AdminService(
            DatabaseOperations database,
            Authorization authorization,
            RequestParameters requestParameters,
            HttpResponses httpResponses,
            AuditTrail auditTrail,
            ObjectMapper mapper) {
        this.database = database;
        this.authorization = authorization;
        this.requestParameters = requestParameters;
        this.httpResponses = httpResponses;
        this.auditTrail = auditTrail;
        this.mapper = mapper;
    }

    public Response listUsers(RequestContext request) {
        authorization.requireRole("admin");
        int page = requestParameters.pagingPage(request);
        int size = requestParameters.pageSize(request);
        String q = requestParameters.singleParam(request, "q");
        requestParameters.maxLength(q, 100, "q");
        String status = requestParameters.singleParam(request, "status");
        if (status != null && !Set.of("active", "blocked").contains(status)) {
            throw StackverseProblem.badRequest("status must be one of: active, blocked");
        }
        PageResponse<UserAccountResponse> body =
                database.withConnection(
                        connection -> {
                            SqlWhere where = new SqlWhere();
                            if (q != null && !q.isBlank()) {
                                where.and(
                                        "u.username ilike ? escape '\\'",
                                        "%" + requestParameters.escapeLike(q) + "%");
                            }
                            if (status != null) {
                                where.and("u.status = ?", status);
                            }
                            long total =
                                    scalarLong(
                                            connection,
                                            "select count(*) from user_accounts u " + where.sql(),
                                            where.params());
                            List<Object> params = new ArrayList<>(where.params());
                            params.add(size);
                            params.add(requestParameters.offset(page, size));
                            List<UserAccountResponse> items =
                                    query(
                                            connection,
                                            userAccountSelect()
                                                    + " "
                                                    + where.sql()
                                                    + " order by u.last_seen desc, u.username asc limit ? offset ?",
                                            params,
                                            rs -> userAccountResponse(userAccount(rs)));
                            return pageResponse(items, page, size, total);
                        });
        return Response.ok(body).build();
    }

    public Response getUser(String username) {
        authorization.requireRole("admin");
        UserAccount account =
                database.withConnection(connection -> findUserAccount(connection, username))
                        .orElseThrow(StackverseProblem::notFound);
        return Response.ok(userAccountResponse(account)).build();
    }

    public Response setUserStatus(String username, UserStatusInput input) {
        Caller caller = authorization.requireRole("admin");
        if (!Set.of("active", "blocked").contains(input.status())) {
            throw StackverseProblem.badRequest("status is required");
        }
        if ("blocked".equals(input.status())) {
            if (username.equals(caller.username())) {
                throw StackverseProblem.conflict("Admins cannot block themselves.");
            }
        }
        UserAccount account =
                database.inTransaction(
                        connection -> {
                            String blockedReason =
                                    "blocked".equals(input.status()) ? input.reason() : null;
                            UserAccount updated =
                                    queryOne(
                                                    connection,
                                                    "with updated as ("
                                                            + " update user_accounts set status = ?, blocked_reason = ? where username = ?"
                                                            + " returning username, first_seen, last_seen, status, blocked_reason"
                                                            + ") select updated.*,"
                                                            + " (select count(*) from bookmarks b where b.owner = updated.username) as bookmark_count"
                                                            + " from updated",
                                                    params(input.status(), blockedReason, username),
                                                    AdminService::userAccount)
                                            .orElseThrow(StackverseProblem::notFound);
                            if ("blocked".equals(input.status())) {
                                auditTrail.record(
                                        connection,
                                        caller.username(),
                                        "user.blocked",
                                        "user",
                                        username,
                                        Map.of("reason", input.reason()));
                            } else {
                                auditTrail.record(
                                        connection,
                                        caller.username(),
                                        "user.unblocked",
                                        "user",
                                        username,
                                        null);
                            }
                            return updated;
                        });
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "blocked".equals(input.status()) ? "user_blocked" : "user_unblocked",
                "success",
                "blocked".equals(input.status())
                        ? "User account blocked"
                        : "User account unblocked",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "user",
                        "resource_id",
                        username));
        return Response.ok(userAccountResponse(account)).build();
    }

    public Response auditLog(RequestContext request) {
        authorization.requireRole("admin");
        int page = requestParameters.pagingPage(request);
        int size = requestParameters.pageSize(request);
        PageResponse<AuditResponse> body =
                database.withConnection(
                        connection -> {
                            SqlWhere where = new SqlWhere();
                            requestParameters.equalFilter(request, where, "actor", "actor");
                            requestParameters.equalFilter(request, where, "action", "action");
                            requestParameters.equalFilter(
                                    request, where, "target_type", "targetType");
                            requestParameters.equalFilter(request, where, "target_id", "targetId");
                            Instant from = requestParameters.timeParam(request, "from");
                            Instant to = requestParameters.timeParam(request, "to");
                            if (from != null) {
                                where.and("created_at >= ?", from);
                            }
                            if (to != null) {
                                where.and("created_at <= ?", to);
                            }
                            long total =
                                    scalarLong(
                                            connection,
                                            "select count(*) from audit_entries " + where.sql(),
                                            where.params());
                            List<Object> params = new ArrayList<>(where.params());
                            params.add(size);
                            params.add(requestParameters.offset(page, size));
                            List<AuditResponse> items =
                                    query(
                                            connection,
                                            "select * from audit_entries "
                                                    + where.sql()
                                                    + " order by created_at desc, id desc limit ? offset ?",
                                            params,
                                            rs -> auditResponse(audit(rs)));
                            return pageResponse(items, page, size, total);
                        });
        return Response.ok(body).build();
    }

    public Response stats(RequestContext request) {
        authorization.requireRole("moderator");
        Map<String, Object> body =
                database.withConnection(
                        connection -> {
                            Map<String, Object> totals = new LinkedHashMap<>();
                            totals.put(
                                    "users",
                                    scalarLong(
                                            connection,
                                            "select count(*) from user_accounts",
                                            List.of()));
                            totals.put(
                                    "bookmarks",
                                    scalarLong(
                                            connection,
                                            "select count(*) from bookmarks",
                                            List.of()));
                            totals.put(
                                    "publicBookmarks",
                                    scalarLong(
                                            connection,
                                            "select count(*) from bookmarks where visibility = 'public'",
                                            List.of()));
                            totals.put(
                                    "hiddenBookmarks",
                                    scalarLong(
                                            connection,
                                            "select count(*) from bookmarks where status = 'hidden'",
                                            List.of()));
                            totals.put(
                                    "openReports",
                                    scalarLong(
                                            connection,
                                            "select count(*) from reports where status = 'open'",
                                            List.of()));

                            LocalDate today = LocalDate.now(ZoneOffset.UTC);
                            LocalDate from = today.minusDays(29);
                            Map<LocalDate, Long> bookmarksCreated =
                                    countPerDay(connection, "bookmarks", "created_at", from);
                            Map<LocalDate, Long> activeUsers =
                                    countPerDay(connection, "user_accounts", "last_seen", from);
                            List<Map<String, Object>> daily = new ArrayList<>();
                            for (int i = 0; i < 30; i++) {
                                LocalDate date = from.plusDays(i);
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("date", date.toString());
                                row.put(
                                        "bookmarksCreated",
                                        bookmarksCreated.getOrDefault(date, 0L));
                                row.put("activeUsers", activeUsers.getOrDefault(date, 0L));
                                daily.add(row);
                            }
                            List<Map<String, Object>> topTags =
                                    query(
                                            connection,
                                            "select tag, count(*) as count from bookmarks, unnest(tags) as tag"
                                                    + " group by tag order by count desc, tag asc limit 10",
                                            List.of(),
                                            rs -> {
                                                Map<String, Object> row = new LinkedHashMap<>();
                                                row.put("tag", rs.getString("tag"));
                                                row.put("count", rs.getLong("count"));
                                                return row;
                                            });
                            Map<String, Object> response = new LinkedHashMap<>();
                            response.put("totals", totals);
                            response.put("daily", daily);
                            response.put("topTags", topTags);
                            return response;
                        });
        return httpResponses.etag(request, body, null);
    }

    private static UserAccount userAccount(ResultSet rs) throws SQLException {
        return new UserAccount(
                rs.getString("username"),
                instant(rs, "first_seen"),
                instant(rs, "last_seen"),
                rs.getString("status"),
                rs.getString("blocked_reason"),
                rs.getLong("bookmark_count"));
    }

    private static AuditEntry audit(ResultSet rs) throws SQLException {
        return new AuditEntry(
                (UUID) rs.getObject("id"),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("detail"),
                instant(rs, "created_at"));
    }

    private static UserAccountResponse userAccountResponse(UserAccount account) {
        return new UserAccountResponse(
                account.username(),
                account.firstSeen(),
                account.lastSeen(),
                account.status(),
                account.blockedReason(),
                account.bookmarkCount());
    }

    @SuppressWarnings("unchecked")
    private AuditResponse auditResponse(AuditEntry entry) {
        Map<String, Object> detail = null;
        if (entry.detail() != null) {
            try {
                detail = mapper.readValue(entry.detail(), Map.class);
            } catch (JsonProcessingException error) {
                detail = Map.of();
            }
        }
        return new AuditResponse(
                entry.id(),
                entry.actor(),
                entry.action(),
                entry.targetType(),
                entry.targetId(),
                detail,
                entry.createdAt());
    }

    private Optional<UserAccount> findUserAccount(Connection connection, String username) {
        return queryOne(
                connection,
                userAccountSelect() + " where u.username = ?",
                List.of(username),
                AdminService::userAccount);
    }

    private static String userAccountSelect() {
        return "select u.username, u.first_seen, u.last_seen, u.status, u.blocked_reason,"
                + " (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count"
                + " from user_accounts u";
    }

    private Map<LocalDate, Long> countPerDay(
            Connection connection, String table, String column, LocalDate from) {
        List<DayCount> rows =
                query(
                        connection,
                        "select ("
                                + column
                                + " at time zone 'UTC')::date as day, count(*) as count"
                                + " from "
                                + table
                                + " where "
                                + column
                                + " >= ? group by day",
                        List.of(from),
                        rs -> new DayCount(rs.getDate("day").toLocalDate(), rs.getLong("count")));
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        for (DayCount row : rows) {
            counts.put(row.date(), row.count());
        }
        return counts;
    }
}
