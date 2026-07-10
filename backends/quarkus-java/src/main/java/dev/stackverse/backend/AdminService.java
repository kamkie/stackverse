package dev.stackverse.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AdminService extends ServiceSupport {
    @Inject
    public AdminService(
            DataSource dataSource,
            JsonWebToken jwt,
            SecurityIdentity securityIdentity,
            ObjectMapper mapper,
            Localizer localizer) {
        super(dataSource, jwt, securityIdentity, mapper, localizer);
    }

    public Response listUsers(RequestContext request) {
        requireRole("admin");
        int page = pagingPage(request);
        int size = pageSize(request);
        String q = singleParam(request, "q");
        maxLength(q, 100, "q");
        String status = singleParam(request, "status");
        if (status != null && !Set.of("active", "blocked").contains(status)) {
            throw StackverseProblem.badRequest("status must be one of: active, blocked");
        }
        PageResponse<UserAccountResponse> body =
                withConnection(
                        connection -> {
                            SqlWhere where = new SqlWhere();
                            if (q != null && !q.isBlank()) {
                                where.and(
                                        "u.username ilike ? escape '\\'",
                                        "%" + escapeLike(q) + "%");
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
                            params.add(offset(page, size));
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
        requireRole("admin");
        UserAccount account =
                withConnection(connection -> findUserAccount(connection, username))
                        .orElseThrow(StackverseProblem::notFound);
        return Response.ok(userAccountResponse(account)).build();
    }

    public Response setUserStatus(String username, UserStatusInput input) {
        Caller caller = requireRole("admin");
        if (!Set.of("active", "blocked").contains(input.status())) {
            throw StackverseProblem.badRequest("status is required");
        }
        if ("blocked".equals(input.status())) {
            if (username.equals(caller.username())) {
                throw StackverseProblem.conflict("Admins cannot block themselves.");
            }
        }
        inTransaction(
                connection -> {
                    queryOne(
                                    connection,
                                    "select username from user_accounts where username = ? for update",
                                    List.of(username),
                                    rs -> rs.getString("username"))
                            .orElseThrow(StackverseProblem::notFound);
                    if ("blocked".equals(input.status())) {
                        execute(
                                connection,
                                "update user_accounts set status = 'blocked', blocked_reason = ? where username = ?",
                                params(input.reason(), username));
                        recordAudit(
                                connection,
                                caller.username(),
                                "user.blocked",
                                "user",
                                username,
                                Map.of("reason", input.reason()));
                    } else {
                        execute(
                                connection,
                                "update user_accounts set status = 'active', blocked_reason = null where username = ?",
                                List.of(username));
                        recordAudit(
                                connection,
                                caller.username(),
                                "user.unblocked",
                                "user",
                                username,
                                null);
                    }
                    return null;
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
        UserAccount account =
                withConnection(connection -> findUserAccount(connection, username))
                        .orElseThrow(StackverseProblem::notFound);
        return Response.ok(userAccountResponse(account)).build();
    }

    public Response auditLog(RequestContext request) {
        requireRole("admin");
        int page = pagingPage(request);
        int size = pageSize(request);
        PageResponse<AuditResponse> body =
                withConnection(
                        connection -> {
                            SqlWhere where = new SqlWhere();
                            equalFilter(request, where, "actor", "actor");
                            equalFilter(request, where, "action", "action");
                            equalFilter(request, where, "target_type", "targetType");
                            equalFilter(request, where, "target_id", "targetId");
                            Instant from = timeParam(request, "from");
                            Instant to = timeParam(request, "to");
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
                            params.add(offset(page, size));
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
        requireRole("moderator");
        Map<String, Object> body =
                withConnection(
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
        return etag(request, body, null);
    }
}
