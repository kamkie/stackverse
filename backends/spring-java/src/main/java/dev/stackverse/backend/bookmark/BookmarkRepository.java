package dev.stackverse.backend.bookmark;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookmarkRepository extends JpaRepository<Bookmark, UUID>, JpaSpecificationExecutor<Bookmark> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Bookmark findForUpdateById(UUID id);

    long countByVisibility(Visibility visibility);

    long countByStatus(BookmarkStatus status);

    @Query(
        nativeQuery = true,
        value = """
            select bt.tag as tag, count(*) as count
            from bookmark_tags bt
            join bookmarks b on b.id = bt.bookmark_id
            where b.owner = :owner
            group by bt.tag
            order by count(*) desc, bt.tag
            """
    )
    List<TagCountRow> countTagsByOwner(@Param("owner") String owner);

    @Query(
        nativeQuery = true,
        value = """
            select bt.tag as tag, count(*) as count
            from bookmark_tags bt
            group by bt.tag
            order by count(*) desc, bt.tag
            limit :limit
            """
    )
    List<TagCountRow> topTags(@Param("limit") int limit);
}
