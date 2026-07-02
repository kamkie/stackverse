package dev.stackverse.backend.account

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface AccountWithBookmarkCount {
    val account: UserAccount
    val bookmarkCount: Long
}

interface UserAccountRepository : JpaRepository<UserAccount, String> {

    /** Single-statement upsert so concurrent first requests of a new user cannot race. */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            insert into user_accounts (username, first_seen, last_seen, status)
            values (:username, :now, :now, 'ACTIVE')
            on conflict (username) do update set last_seen = excluded.last_seen
            """,
    )
    fun upsertSeen(username: String, now: Instant)

    @Query(
        """
        select u as account, (select count(b) from Bookmark b where b.owner = u.username) as bookmarkCount
        from UserAccount u
        where (:qLike is null or lower(u.username) like :qLike escape '\')
          and (:status is null or u.status = :status)
        order by u.lastSeen desc
        """,
        countQuery = """
            select count(u) from UserAccount u
            where (:qLike is null or lower(u.username) like :qLike escape '\')
              and (:status is null or u.status = :status)
            """,
    )
    fun search(qLike: String?, status: UserAccountStatus?, pageable: Pageable): Page<AccountWithBookmarkCount>

    @Query(
        """
        select u as account, (select count(b) from Bookmark b where b.owner = u.username) as bookmarkCount
        from UserAccount u
        where u.username = :username
        """,
    )
    fun findWithBookmarkCount(username: String): AccountWithBookmarkCount?
}
