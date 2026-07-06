package dev.stackverse.backend.account;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, String> {
    /** Single-statement upsert so concurrent first requests of a new user cannot race. */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            insert into user_accounts (username, first_seen, last_seen, status)
            values (:username, :now, :now, 'ACTIVE')
            on conflict (username) do update set last_seen = excluded.last_seen
            """
    )
    void upsertSeen(@Param("username") String username, @Param("now") Instant now);

    @Query(
        value = """
            select u as account, (select count(b) from Bookmark b where b.owner = u.username) as bookmarkCount
            from UserAccount u
            where (:qLike is null or lower(u.username) like :qLike escape '\\')
              and (:status is null or u.status = :status)
            order by u.lastSeen desc
            """,
        countQuery = """
            select count(u) from UserAccount u
            where (:qLike is null or lower(u.username) like :qLike escape '\\')
              and (:status is null or u.status = :status)
            """
    )
    Page<AccountWithBookmarkCount> search(
        @Param("qLike") String qLike,
        @Param("status") UserAccountStatus status,
        Pageable pageable
    );

    @Query(
        """
        select u as account, (select count(b) from Bookmark b where b.owner = u.username) as bookmarkCount
        from UserAccount u
        where u.username = :username
        """
    )
    AccountWithBookmarkCount findWithBookmarkCount(@Param("username") String username);
}
