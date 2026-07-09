package dev.stackverse.openliberty;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequestScoped
public class AdminResource extends ResourceSupport {
    @GET
    @Path("/api/v1/admin/users")
    @RequiresRole("admin")
    public Response users() throws SQLException {
        requireRole("admin");
        Paging paging = paging();
        String q = single("q");
        requireMax(q, 100, "q");
        String status = single("status");
        if (status != null && !"active".equals(status) && !"blocked".equals(status)) {
            throw ApiProblem.badRequest("unknown status: " + status);
        }
        List<String> conditions = new ArrayList<>(List.of("true"));
        List<Object> params = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            conditions.add("u.username ilike ? escape '\\'");
            params.add("%" + escapeLike(q) + "%");
        }
        if (status != null) {
            conditions.add("u.status = ?");
            params.add(status);
        }
        String where = String.join(" and ", conditions);
        String select =
                "select u.*, (select count(*)::int from bookmarks b where b.owner = u.username) as bookmark_count from user_accounts u";
        List<ApiModels.UserAccount> items = new ArrayList<>();
        long total;
        try (Connection connection = runtime.connection()) {
            try (PreparedStatement statement =
                    prepare(
                            connection,
                            select
                                    + " where "
                                    + where
                                    + " order by u.last_seen desc, u.username asc limit ? offset ?",
                            append(params, paging.size(), paging.offset()))) {
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) items.add(userAccount(rs));
                }
            }
            try (PreparedStatement statement =
                    prepare(
                            connection,
                            "select count(*) as count from user_accounts u where " + where,
                            params)) {
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    total = rs.getLong("count");
                }
            }
        }
        return JsonSupport.json(page(items, paging, total));
    }

    @GET
    @Path("/api/v1/admin/users/{username}")
    @RequiresRole("admin")
    public Response user(@PathParam("username") String username) throws SQLException {
        requireRole("admin");
        ApiModels.UserAccount account = findUser(username);
        if (account == null) throw ApiProblem.notFound();
        return JsonSupport.json(account);
    }

    @PUT
    @Path("/api/v1/admin/users/{username}/status")
    @RequiresRole("admin")
    public Response setUserStatus(@PathParam("username") String username, UserStatusInput body)
            throws SQLException {
        Caller caller = requireRole("admin");
        UserStatusInput input = validateDto(body);
        String status = input.status();
        if (!"active".equals(status) && !"blocked".equals(status)) {
            throw ApiProblem.badRequest("status is required");
        }
        String reason = input.reason();
        if ("blocked".equals(status)) {
            Validator validator = new Validator();
            validator.check(
                    reason != null && !reason.isBlank(),
                    "reason",
                    "validation.block.reason.required");
            validator.throwIfInvalid();
            if (username.equals(caller.username()))
                throw ApiProblem.conflict("Admins cannot block themselves.");
        }
        runtime.transaction(
                connection -> {
                    if (!exists(
                            connection,
                            "select 1 from user_accounts where username = ? for update",
                            username)) throw ApiProblem.notFound();
                    if ("blocked".equals(status)) {
                        try (PreparedStatement statement =
                                runtime.prepare(
                                        connection,
                                        "update user_accounts set status = 'blocked', blocked_reason = ? where username = ?",
                                        reason,
                                        username)) {
                            statement.executeUpdate();
                        }
                        audit(
                                connection,
                                caller.username(),
                                "user.blocked",
                                "user",
                                username,
                                linked("reason", reason));
                    } else {
                        try (PreparedStatement statement =
                                runtime.prepare(
                                        connection,
                                        "update user_accounts set status = 'active', blocked_reason = null where username = ?",
                                        username)) {
                            statement.executeUpdate();
                        }
                        audit(
                                connection,
                                caller.username(),
                                "user.unblocked",
                                "user",
                                username,
                                null);
                    }
                    return null;
                });
        log.event(
                "info",
                "blocked".equals(status) ? "user_blocked" : "user_unblocked",
                "success",
                "User account status changed",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "user",
                        "resource_id",
                        username));
        return JsonSupport.json(findUser(username));
    }

    @GET
    @Path("/api/v1/admin/audit-log")
    @RequiresRole("admin")
    public Response auditLog() throws SQLException {
        requireRole("admin");
        Paging paging = paging();
        List<String> conditions = new ArrayList<>(List.of("true"));
        List<Object> params = new ArrayList<>();
        for (String[] pair :
                List.of(
                        new String[] {"actor", "actor"},
                        new String[] {"action", "action"},
                        new String[] {"targetType", "target_type"},
                        new String[] {"targetId", "target_id"})) {
            String value = single(pair[0]);
            if (value != null) {
                conditions.add(pair[1] + " = ?");
                params.add(value);
            }
        }
        String from = single("from");
        if (from != null) {
            conditions.add("created_at >= ?");
            params.add(parseInstant(from, "from"));
        }
        String to = single("to");
        if (to != null) {
            conditions.add("created_at <= ?");
            params.add(parseInstant(to, "to"));
        }
        ResponsePage result =
                queryPage(
                        "audit_entries",
                        String.join(" and ", conditions),
                        "order by created_at desc, id desc",
                        params,
                        paging,
                        this::auditEntry);
        return JsonSupport.json(page(result.items(), paging, result.total()));
    }

    @GET
    @Path("/api/v1/admin/stats")
    @RequiresRole("moderator")
    public Response stats() throws SQLException {
        requireRole("moderator");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(29);
        Instant startInstant = start.atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, Integer> bookmarkDays = countPerDay("bookmarks", "created_at", startInstant);
        Map<String, Integer> activeDays = countPerDay("user_accounts", "last_seen", startInstant);
        List<ApiModels.DailyStat> daily = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String date = start.plusDays(i).toString();
            daily.add(
                    new ApiModels.DailyStat(
                            date,
                            bookmarkDays.getOrDefault(date, 0),
                            activeDays.getOrDefault(date, 0)));
        }
        ApiModels.AdminTotals totals =
                new ApiModels.AdminTotals(
                        count("select count(*) from user_accounts"),
                        count("select count(*) from bookmarks"),
                        count("select count(*) from bookmarks where visibility = 'public'"),
                        count("select count(*) from bookmarks where status = 'hidden'"),
                        count("select count(*) from reports where status = 'open'"));
        List<ApiModels.TagCount> topTags = new ArrayList<>();
        try (Connection connection = runtime.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
             select tag, count(*)::int as count
             from bookmarks, unnest(tags) as tag
             group by tag
             order by count desc, tag asc
             limit 10
             """)) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next())
                    topTags.add(new ApiModels.TagCount(rs.getString("tag"), rs.getInt("count")));
            }
        }
        return JsonSupport.etagResponse(
                headers.getHeaderString("If-None-Match"),
                new ApiModels.AdminStats(totals, daily, topTags));
    }
}
