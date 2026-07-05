package dev.stackverse.backend;

import jakarta.inject.Singleton;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Singleton
final class AccountService {
    private final Database db;

    AccountService(Database db) {
        this.db = db;
    }

    Account recordSeen(String username) {
        Instant now = WebSupport.now();
        return db.one("""
                insert into user_accounts (username, first_seen, last_seen, status)
                values (?, ?, ?, 'active')
                on conflict (username) do update set last_seen = excluded.last_seen
                returning username, first_seen, last_seen, status, blocked_reason,
                    (select count(*) from bookmarks b where b.owner = user_accounts.username) as bookmark_count
                """, this::mapAccount, username, now, now);
    }

    Account get(String username) {
        return db.one("""
                select username, first_seen, last_seen, status, blocked_reason,
                    (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count
                from user_accounts u where username = ?
                """, this::mapAccount, username);
    }

    List<Account> search(String q, String status, int page, int size) {
        String qLike = q == null || q.isBlank() ? "" : "%" + WebSupport.escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
        return db.query("""
                select username, first_seen, last_seen, status, blocked_reason,
                    (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count
                from user_accounts u
                where (? = '' or lower(username) like ? escape '\\')
                  and (? = '' or status = ?)
                order by last_seen desc
                limit ? offset ?
                """, this::mapAccount, qLike, qLike, status, status, size, WebSupport.offset(page, size));
    }

    long countSearch(String q, String status) {
        String qLike = q == null || q.isBlank() ? "" : "%" + WebSupport.escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
        return db.scalarLong("""
                select count(*) from user_accounts
                where (? = '' or lower(username) like ? escape '\\')
                  and (? = '' or status = ?)
                """, qLike, qLike, status, status);
    }

    void setStatus(Connection connection, String username, String status, String reason) throws SQLException {
        db.update(connection, "update user_accounts set status = ?, blocked_reason = ? where username = ?",
                status, reason, username);
    }

    private Account mapAccount(ResultSet rs) throws SQLException {
        return new Account(
                rs.getString("username"),
                rs.getTimestamp("first_seen").toInstant(),
                rs.getTimestamp("last_seen").toInstant(),
                rs.getString("status"),
                rs.getString("blocked_reason"),
                rs.getLong("bookmark_count")
        );
    }
}
