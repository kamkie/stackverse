package dev.stackverse.backend.bookmark

import dev.stackverse.backend.common.PageResponse
import dev.stackverse.backend.common.requireMaxLength
import dev.stackverse.backend.common.requireValidPaging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

/**
 * `GET /api/v1/bookmarks` is the deprecated predecessor of the v2 listing; its
 * `Deprecation` / `Sunset` / `Link` headers are added by a filter in
 * [dev.stackverse.backend.config.WebConfig] so every response carries them.
 */
@RestController
@RequestMapping("/api/v1/bookmarks")
class BookmarkV1Controller(private val service: BookmarkService) {

    @GetMapping
    fun list(
        @RequestParam tag: List<String>?,
        @RequestParam q: String?,
        @RequestParam visibility: Visibility?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        authentication: Authentication?,
    ): PageResponse<BookmarkResponse> {
        requireValidPaging(page, size)
        requireMaxLength(q, 200, "q")
        val result = service.listOffset(authentication?.name, BookmarkListQuery(tag.orEmpty(), q, visibility), page, size)
        return PageResponse.of(result) { BookmarkResponse.of(it) }
    }

    @PostMapping
    fun create(@RequestBody request: BookmarkRequest, authentication: Authentication): ResponseEntity<BookmarkResponse> {
        val bookmark = service.create(authentication.name, request)
        val location = UriComponentsBuilder.fromPath("/api/v1/bookmarks/{id}").build(bookmark.id)
        return ResponseEntity.created(location).body(BookmarkResponse.of(bookmark))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID, authentication: Authentication?): BookmarkResponse =
        BookmarkResponse.of(service.get(authentication?.name, id))

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: BookmarkRequest,
        authentication: Authentication,
    ): BookmarkResponse = BookmarkResponse.of(service.update(authentication.name, id, request))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID, authentication: Authentication) {
        service.delete(authentication.name, id)
    }
}

@RestController
class BookmarkV2Controller(private val service: BookmarkService) {

    @GetMapping("/api/v2/bookmarks")
    fun list(
        @RequestParam tag: List<String>?,
        @RequestParam q: String?,
        @RequestParam visibility: Visibility?,
        @RequestParam cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
        authentication: Authentication?,
    ): BookmarkCursorPageResponse {
        requireValidPaging(page = 0, size = size)
        requireMaxLength(q, 200, "q")
        val slice = service.listKeyset(
            authentication?.name,
            BookmarkListQuery(tag.orEmpty(), q, visibility),
            cursor?.let { BookmarkCursor.decode(it) },
            size,
        )
        return BookmarkCursorPageResponse(
            items = slice.items.map { BookmarkResponse.of(it) },
            nextCursor = slice.nextCursor?.encode(),
        )
    }
}

@RestController
class TagController(private val repository: BookmarkRepository) {

    @GetMapping("/api/v1/tags")
    fun list(authentication: Authentication): TagListResponse =
        TagListResponse(repository.countTagsByOwner(authentication.name).map { TagCountResponse(it.tag, it.count) })
}
