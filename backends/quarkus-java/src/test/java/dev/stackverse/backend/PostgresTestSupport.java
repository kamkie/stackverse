package dev.stackverse.backend;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.eclipse.microprofile.jwt.JsonWebToken;

final class PostgresTestSupport {
    static final Instant BASE_TIME = Instant.parse("2026-07-01T12:00:00Z");

    private PostgresTestSupport() {}

    static void reset(DataSource dataSource) throws SQLException {
        execute(
                dataSource,
                "truncate table audit_entries, reports, bookmarks, messages, user_accounts cascade");
    }

    static void insertUser(
            DataSource dataSource,
            String username,
            String status,
            String blockedReason,
            Instant seen)
            throws SQLException {
        execute(
                dataSource,
                "insert into user_accounts (username, first_seen, last_seen, status, blocked_reason)"
                        + " values (?, ?, ?, ?, ?)",
                username,
                seen,
                seen,
                status,
                blockedReason);
    }

    static void insertBookmark(
            DataSource dataSource,
            UUID id,
            String owner,
            String visibility,
            String status,
            Instant createdAt,
            String title,
            List<String> tags)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "insert into bookmarks"
                                        + " (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)"
                                        + " values (?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, owner);
            statement.setString(3, "https://example.com/" + id);
            statement.setString(4, title);
            statement.setString(5, "notes for " + title);
            statement.setArray(6, connection.createArrayOf("text", tags.toArray(String[]::new)));
            statement.setString(7, visibility);
            statement.setString(8, status);
            statement.setTimestamp(9, Timestamp.from(createdAt));
            statement.setTimestamp(10, Timestamp.from(createdAt));
            statement.executeUpdate();
        }
    }

    static void insertMessage(
            DataSource dataSource,
            UUID id,
            String key,
            String language,
            String text,
            String description,
            Instant at)
            throws SQLException {
        execute(
                dataSource,
                "insert into messages (id, key, language, text, description, created_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?)",
                id,
                key,
                language,
                text,
                description,
                at,
                at);
    }

    static void insertReport(
            DataSource dataSource,
            UUID id,
            UUID bookmarkId,
            String reporter,
            String reason,
            String comment,
            String status,
            String resolvedBy,
            Instant resolvedAt,
            String resolutionNote,
            Instant createdAt)
            throws SQLException {
        execute(
                dataSource,
                "insert into reports"
                        + " (id, bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                bookmarkId,
                reporter,
                reason,
                comment,
                status,
                resolvedBy,
                resolvedAt,
                resolutionNote,
                createdAt);
    }

    static void execute(DataSource dataSource, String sql, Object... params) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(connection, statement, params);
            statement.executeUpdate();
        }
    }

    static long scalarLong(DataSource dataSource, String sql, Object... params)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(connection, statement, params);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    static String scalarString(DataSource dataSource, String sql, Object... params)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(connection, statement, params);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    static Authorization authorization(String username, String... roles) {
        return new Authorization(identity(username, roles), jwt(username));
    }

    static SecurityIdentity identity(String username, String... roles) {
        Set<String> roleSet = new LinkedHashSet<>(List.of(roles));
        return proxy(
                SecurityIdentity.class,
                (method, args) ->
                        switch (method.getName()) {
                            case "isAnonymous" -> username == null;
                            case "getPrincipal" ->
                                    username == null ? null : (Principal) () -> username;
                            case "getRoles" -> roleSet;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    static JsonWebToken jwt(String username) {
        return proxy(
                JsonWebToken.class,
                (method, args) ->
                        switch (method.getName()) {
                            case "getClaim" ->
                                    switch (String.valueOf(args[0])) {
                                        case "preferred_username" -> username;
                                        case "name" -> username == null ? null : "Name " + username;
                                        case "email" ->
                                                username == null ? null : username + "@example.com";
                                        default -> null;
                                    };
                            case "getName" -> username;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    static ContainerRequestContext requestCapture(AtomicReference<Response> aborted) {
        return proxy(
                ContainerRequestContext.class,
                (method, args) -> {
                    if ("abortWith".equals(method.getName())) {
                        aborted.set((Response) args[0]);
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    static ContainerRequestContext routeRequest(String methodName, String path) {
        UriInfo uriInfo =
                proxy(
                        UriInfo.class,
                        (method, args) ->
                                "getPath".equals(method.getName())
                                        ? path
                                        : defaultValue(method.getReturnType()));
        return proxy(
                ContainerRequestContext.class,
                (method, args) ->
                        switch (method.getName()) {
                            case "getMethod" -> methodName;
                            case "getUriInfo" -> uriInfo;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    static RequestContext request() {
        return request(Map.of(), Map.of());
    }

    static RequestContext request(Map<String, List<String>> query) {
        return request(query, Map.of());
    }

    static RequestContext request(
            Map<String, List<String>> query, Map<String, String> headerValues) {
        MultivaluedHashMap<String, String> parameters = new MultivaluedHashMap<>();
        query.forEach(parameters::put);
        UriInfo uriInfo =
                proxy(
                        UriInfo.class,
                        (method, args) ->
                                switch (method.getName()) {
                                    case "getQueryParameters" -> parameters;
                                    case "getPath" -> "";
                                    default -> defaultValue(method.getReturnType());
                                });
        HttpHeaders headers =
                proxy(
                        HttpHeaders.class,
                        (method, args) ->
                                "getHeaderString".equals(method.getName())
                                        ? headerValues.get(String.valueOf(args[0]))
                                        : defaultValue(method.getReturnType()));
        return new RequestContext(uriInfo, headers);
    }

    private static void bind(Connection connection, PreparedStatement statement, Object[] params)
            throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            int index = i + 1;
            if (value instanceof Instant instant) {
                statement.setTimestamp(index, Timestamp.from(instant));
            } else if (value instanceof List<?> list) {
                statement.setArray(
                        index,
                        connection.createArrayOf(
                                "text", list.stream().map(String::valueOf).toArray(String[]::new)));
            } else {
                statement.setObject(index, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T)
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, args) -> {
                            if ("toString".equals(method.getName())) {
                                return type.getSimpleName() + " test proxy";
                            }
                            if ("hashCode".equals(method.getName())) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(method.getName())) {
                                return proxy == args[0];
                            }
                            return invocation.invoke(method, args == null ? new Object[0] : args);
                        });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0F;
        }
        if (returnType == double.class) {
            return 0.0D;
        }
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
