package dev.stackverse.backend.bookmark

import dev.stackverse.backend.common.ConflictProblem
import dev.stackverse.backend.common.NotFoundProblem
import dev.stackverse.backend.common.UnauthorizedProblem
import dev.stackverse.backend.common.Validator
import dev.stackverse.backend.common.nowUtc
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Instant
import java.util.UUID

private val TAG_PATTERN = Regex("^[a-z0-9-]{1,30}$")

/** Newest first; `id` breaks `createdAt` ties so the order is total — a keyset requirement. */
private val NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id")

data class BookmarkListQuery(
    val tags: List<String>,
    val q: String?,
    val visibility: Visibility?,
)

data class BookmarkSlice(val items: List<Bookmark>, val nextCursor: BookmarkCursor?)

fun validateQueryTags(tags: List<String>): List<String> {
    val normalized = tags.map { it.trim().lowercase() }
    val validator = Validator()
    validator.check(normalized.all { it.matches(TAG_PATTERN) }, "tag", "validation.tag.invalid")
    validator.throwIfInvalid()
    return normalized
}

@Service
@Transactional
class BookmarkService(private val repository: BookmarkRepository) {

    fun create(owner: String, request: BookmarkRequest): Bookmark {
        val input = validate(request)
        val now = nowUtc()
        return repository.save(
            Bookmark(
                owner = owner,
                url = input.url,
                title = input.title,
                notes = input.notes,
                tags = input.tags.toMutableSet(),
                visibility = input.visibility,
                status = BookmarkStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun get(caller: String?, id: UUID): Bookmark {
        val bookmark = repository.findById(id).orElseThrow { NotFoundProblem() }
        if (!bookmark.isVisibleTo(caller)) {
            throw NotFoundProblem()
        }
        return bookmark
    }

    fun update(caller: String, id: UUID, request: BookmarkRequest): Bookmark {
        // Row lock: the moderator status endpoint takes the same PESSIMISTIC_WRITE
        // lock, so a concurrent hide cannot slip between the hidden-publish check
        // and the write (SPEC rule 15). Re-checking status under the lock is the point.
        val bookmark = repository.findForUpdateById(id) ?: throw NotFoundProblem()
        // rule 1: a non-owner never learns the bookmark exists — 404, not 403
        if (bookmark.owner != caller) {
            throw NotFoundProblem()
        }
        val input = validate(request)
        // SPEC rule 15: a moderation-hidden bookmark cannot be (re)published by its owner
        if (bookmark.status == BookmarkStatus.HIDDEN && input.visibility == Visibility.PUBLIC) {
            throw ConflictProblem(
                "This bookmark was hidden by moderation and cannot be made public.",
                detailKey = "error.bookmark.hidden-publish",
            )
        }
        bookmark.url = input.url
        bookmark.title = input.title
        bookmark.notes = input.notes
        bookmark.tags = input.tags.toMutableSet()
        bookmark.visibility = input.visibility
        bookmark.updatedAt = nowUtc()
        return bookmark
    }

    fun delete(caller: String, id: UUID) {
        repository.delete(ownedByCaller(caller, id))
    }

    @Transactional(readOnly = true)
    fun listOffset(caller: String?, query: BookmarkListQuery, page: Int, size: Int): Page<Bookmark> =
        repository.findAll(specificationFor(caller, query), PageRequest.of(page, size, NEWEST_FIRST))

    @Transactional(readOnly = true)
    fun listKeyset(caller: String?, query: BookmarkListQuery, cursor: BookmarkCursor?, size: Int): BookmarkSlice {
        var specification = specificationFor(caller, query)
        if (cursor != null) {
            specification = specification.and(before(cursor))
        }
        val fetched: List<Bookmark> = repository.findBy(specification) { query ->
            query.sortBy(NEWEST_FIRST).limit(size + 1).all()
        }
        val items = fetched.take(size)
        val nextCursor = if (fetched.size > size) BookmarkCursor.of(items.last()) else null
        return BookmarkSlice(items, nextCursor)
    }

    private fun Bookmark.isVisibleTo(caller: String?): Boolean =
        owner == caller || (visibility == Visibility.PUBLIC && status == BookmarkStatus.ACTIVE)

    /** Rule 1: a non-owner never learns the bookmark exists — 404, not 403. */
    private fun ownedByCaller(caller: String, id: UUID): Bookmark {
        val bookmark = repository.findById(id).orElseThrow { NotFoundProblem() }
        if (bookmark.owner != caller) {
            throw NotFoundProblem()
        }
        return bookmark
    }

    /**
     * Rule 2 + 3: `visibility=public` is the anonymous-capable public feed across all
     * owners (hidden excluded); every other listing is the caller's own bookmarks.
     */
    private fun specificationFor(caller: String?, query: BookmarkListQuery): Specification<Bookmark> {
        val scope = if (query.visibility == Visibility.PUBLIC) {
            Specification<Bookmark> { root, _, cb ->
                cb.and(
                    cb.equal(root.get<Visibility>("visibility"), Visibility.PUBLIC),
                    cb.equal(root.get<BookmarkStatus>("status"), BookmarkStatus.ACTIVE),
                )
            }
        } else {
            val owner = caller ?: throw UnauthorizedProblem()
            Specification<Bookmark> { root, _, cb ->
                var predicate = cb.equal(root.get<String>("owner"), owner)
                if (query.visibility != null) {
                    predicate = cb.and(predicate, cb.equal(root.get<Visibility>("visibility"), query.visibility))
                }
                predicate
            }
        }
        return Specification.allOf(
            buildList {
                add(scope)
                for (tag in query.tags) {
                    add(Specification { root, _, cb -> cb.isMember(tag, root.get("tags")) })
                }
                query.q?.takeIf { it.isNotBlank() }?.let { q ->
                    val pattern = "%${escapeLike(q.lowercase())}%"
                    add(
                        Specification { root, _, cb ->
                            cb.or(
                                cb.like(cb.lower(root.get("title")), pattern, '\\'),
                                cb.like(cb.lower(root.get("notes")), pattern, '\\'),
                            )
                        },
                    )
                }
            },
        )
    }

    private fun before(cursor: BookmarkCursor): Specification<Bookmark> =
        Specification { root, _, cb ->
            cb.or(
                cb.lessThan(root.get<Instant>("createdAt"), cursor.createdAt),
                cb.and(
                    cb.equal(root.get<Instant>("createdAt"), cursor.createdAt),
                    cb.lessThan(root.get<UUID>("id"), cursor.id),
                ),
            )
        }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private data class ValidatedBookmark(
        val url: String,
        val title: String,
        val notes: String?,
        val tags: Set<String>,
        val visibility: Visibility,
    )

    private fun validate(request: BookmarkRequest): ValidatedBookmark {
        val validator = Validator()

        val url = request.url?.trim().orEmpty()
        if (url.isEmpty()) {
            validator.reject("url", "validation.url.required")
        } else {
            validator.check(url.length <= 2000 && isHttpUrl(url), "url", "validation.url.invalid")
        }

        val title = request.title?.trim().orEmpty()
        validator.check(title.isNotEmpty(), "title", "validation.title.required")
        validator.check(title.length <= 200, "title", "validation.title.too-long")

        validator.check((request.notes?.length ?: 0) <= 4000, "notes", "validation.notes.too-long")

        // normalized before validation: " Kotlin " and "kotlin" are the same tag
        val tags = request.tags.orEmpty().map { it.trim().lowercase() }.toCollection(LinkedHashSet())
        validator.check(tags.size <= 10, "tags", "validation.tags.too-many")
        validator.check(tags.all { it.matches(TAG_PATTERN) }, "tags", "validation.tag.invalid")

        validator.throwIfInvalid()
        return ValidatedBookmark(url, title, request.notes, tags, request.visibility ?: Visibility.PRIVATE)
    }

    private fun isHttpUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.isAbsolute && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }
}
