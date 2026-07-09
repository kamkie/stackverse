package dev.stackverse.backend;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
final class AdminController {
    private static final Logger LOG = LoggerFactory.getLogger(AdminController.class);

    private final Database db;
    private final SecuritySupport security;
    private final AccountService accounts;
    private final AuditService audit;
    private final ObjectMapper mapper;

    AdminController(Database db, SecuritySupport security, AccountService accounts, AuditService audit, ObjectMapper mapper) {
        this.db = db;
        this.security = security;
        this.accounts = accounts;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Get("/api/v1/admin/users")
    PageResponse<AccountResponse> users(HttpRequest<?> request) {
        security.requireRole(request, "admin");
        int page = WebSupport.page(request);
        int size = WebSupport.size(request);
        String q = request.getParameters().getFirst("q").orElse("");
        if (WebSupport.length(q) > 100) {
            throw Problems.badRequest("q is too long");
        }
        String status = request.getParameters().getFirst("status").orElse("");
        if (!status.isBlank() && !List.of(Models.USER_ACTIVE, Models.USER_BLOCKED).contains(status)) {
            throw Problems.badRequest("status must be one of: active, blocked");
        }
        List<AccountResponse> items = accounts.search(q, status, page, size).stream().map(this::accountResponse).toList();
        long total = accounts.countSearch(q, status);
        return WebSupport.pageResponse(items, page, size, total);
    }

    @Get("/api/v1/admin/users/{username}")
    AccountResponse user(HttpRequest<?> request, @PathVariable String username) {
        security.requireRole(request, "admin");
        return accountResponse(accounts.get(username));
    }

    @Put("/api/v1/admin/users/{username}/status")
    AccountResponse setUserStatus(HttpRequest<?> request, @PathVariable String username, @Body UserStatusInput body) {
        Identity actor = security.requireRole(request, "admin");
        if (body == null || body.status() == null) {
            throw Problems.badRequest("status is required");
        }
        if (!List.of(Models.USER_ACTIVE, Models.USER_BLOCKED).contains(body.status())) {
            throw Problems.badRequest("status must be one of: active, blocked");
        }
        accounts.get(username);
        String reason = body.reason() == null ? null : body.reason().trim();
        boolean blocking = Models.USER_BLOCKED.equals(body.status());
        if (blocking) {
            Validator validator = new Validator();
            validator.check(reason != null && !reason.isBlank(), "reason", "validation.block.reason.required");
            validator.check(WebSupport.length(reason) <= 1000, "reason", "validation.block.reason.too-long");
            validator.throwIfInvalid();
            if (username.equals(actor.username())) {
                throw Problems.conflict("Admins cannot block themselves.");
            }
        }
        String status = blocking ? Models.USER_BLOCKED : Models.USER_ACTIVE;
        String auditAction = blocking ? "user.blocked" : "user.unblocked";
        String event = blocking ? "user_blocked" : "user_unblocked";
        String message = blocking ? "User account blocked" : "User account unblocked";
        db.inTx(connection -> {
            try {
                accounts.setStatus(connection, username, status, blocking ? reason : null);
                audit.record(connection, actor.username(), auditAction, "user", username,
                        blocking ? Map.of("reason", reason) : null);
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
            return null;
        });
        EventLog.info(LOG, event, "success", message,
                Map.of("actor", actor.username(), "resource_type", "user", "resource_id", username));
        return accountResponse(accounts.get(username));
    }

    @Get("/api/v1/admin/audit-log")
    PageResponse<AuditResponse> auditLog(HttpRequest<?> request) {
        security.requireRole(request, "admin");
        int page = WebSupport.page(request);
        int size = WebSupport.size(request);
        String actor = request.getParameters().getFirst("actor").orElse("");
        String action = request.getParameters().getFirst("action").orElse("");
        String targetType = request.getParameters().getFirst("targetType").orElse("");
        String targetId = request.getParameters().getFirst("targetId").orElse("");
        Instant from = instantParam(request, "from");
        Instant to = instantParam(request, "to");
        String where = """
                where (? = '' or actor = ?)
                  and (? = '' or action = ?)
                  and (? = '' or target_type = ?)
                  and (? = '' or target_id = ?)
                  and (cast(? as timestamptz) is null or created_at >= ?)
                  and (cast(? as timestamptz) is null or created_at <= ?)
                """;
        List<Object> filterArgs = auditFilterArgs(actor, action, targetType, targetId, from, to);
        long total = db.scalarLong("select count(*) from audit_entries " + where, filterArgs.toArray());
        List<Object> queryArgs = new ArrayList<>(filterArgs);
        queryArgs.add(size);
        queryArgs.add(WebSupport.offset(page, size));
        List<AuditResponse> items = db.query("""
                select id, actor, action, target_type, target_id, detail, created_at
                from audit_entries %s
                order by created_at desc, id desc
                limit ? offset ?
                """.formatted(where), rs -> new AuditResponse(
                rs.getObject("id", UUID.class),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                json(rs.getString("detail")),
                rs.getTimestamp("created_at").toInstant()
        ), queryArgs.toArray());
        return WebSupport.pageResponse(items, page, size, total);
    }

    @Get("/api/v1/admin/stats")
    MutableHttpResponse<?> stats(HttpRequest<?> request) {
        security.requireRole(request, "moderator");
        StatsTotals totals = db.one("""
                select
                    (select count(*) from user_accounts) as users,
                    (select count(*) from bookmarks) as bookmarks,
                    (select count(*) from bookmarks where visibility = 'public') as public_bookmarks,
                    (select count(*) from bookmarks where status = 'hidden') as hidden_bookmarks,
                    (select count(*) from reports where status = 'open') as open_reports
                """, rs -> new StatsTotals(rs.getLong("users"), rs.getLong("bookmarks"),
                rs.getLong("public_bookmarks"), rs.getLong("hidden_bookmarks"), rs.getLong("open_reports")));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(29);
        Map<String, Long> bookmarksCreated = countsByDay("bookmarks", "created_at", from);
        Map<String, Long> activeUsers = countsByDay("user_accounts", "last_seen", from);
        List<DailyStat> daily = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String date = from.plusDays(i).toString();
            daily.add(new DailyStat(date, bookmarksCreated.getOrDefault(date, 0L), activeUsers.getOrDefault(date, 0L)));
        }
        List<TagCount> tags = db.query("""
                select tag, count(*) as count
                from bookmarks b cross join unnest(b.tags) as tag
                group by tag
                order by count(*) desc, tag
                limit 10
                """, rs -> new TagCount(rs.getString("tag"), rs.getLong("count")));
        return WebSupport.etag(mapper, request, new AdminStats(totals, daily, tags), null);
    }

    private AccountResponse accountResponse(Account account) {
        return new AccountResponse(account.username(), account.firstSeen(), account.lastSeen(), account.status(),
                account.blockedReason(), account.bookmarkCount());
    }

    private Instant instantParam(HttpRequest<?> request, String name) {
        String raw = request.getParameters().getFirst(name).orElse("");
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException ex) {
            throw Problems.badRequest(name + " must be an RFC 3339 timestamp");
        }
    }

    private Object json(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return mapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return raw;
        }
    }

    private List<Object> auditFilterArgs(String actor, String action, String targetType, String targetId, Instant from, Instant to) {
        List<Object> args = new ArrayList<>();
        args.add(actor);
        args.add(actor);
        args.add(action);
        args.add(action);
        args.add(targetType);
        args.add(targetType);
        args.add(targetId);
        args.add(targetId);
        args.add(from);
        args.add(from);
        args.add(to);
        args.add(to);
        return args;
    }

    private Map<String, Long> countsByDay(String table, String column, LocalDate from) {
        Map<String, Long> counts = new HashMap<>();
        db.query("""
                select (%s at time zone 'UTC')::date as day, count(*) as count
                from %s
                where %s >= ?
                group by day
                """.formatted(column, table, column), rs -> {
            counts.put(rs.getObject("day", LocalDate.class).toString(), rs.getLong("count"));
            return null;
        }, from.atStartOfDay().toInstant(ZoneOffset.UTC));
        return counts;
    }
}
