package dev.stackverse.backend.bookmark

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface TagCountRow {
    val tag: String
    val count: Long
}

interface BookmarkRepository : JpaRepository<Bookmark, UUID>, JpaSpecificationExecutor<Bookmark> {

    fun countByVisibility(visibility: Visibility): Long

    fun countByStatus(status: BookmarkStatus): Long

    @Query(
        nativeQuery = true,
        value = """
            select bt.tag as tag, count(*) as count
            from bookmark_tags bt
            join bookmarks b on b.id = bt.bookmark_id
            where b.owner = :owner
            group by bt.tag
            order by count(*) desc, bt.tag
            """,
    )
    fun countTagsByOwner(owner: String): List<TagCountRow>

    @Query(
        nativeQuery = true,
        value = """
            select bt.tag as tag, count(*) as count
            from bookmark_tags bt
            group by bt.tag
            order by count(*) desc, bt.tag
            limit :limit
            """,
    )
    fun topTags(limit: Int): List<TagCountRow>
}
