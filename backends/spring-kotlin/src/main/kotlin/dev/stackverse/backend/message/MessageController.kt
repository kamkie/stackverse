package dev.stackverse.backend.message

import dev.stackverse.backend.common.PageResponse
import dev.stackverse.backend.common.requireMaxLength
import dev.stackverse.backend.common.requireValidPaging
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.data.domain.Pageable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import dev.stackverse.backend.common.NotFoundProblem
import java.util.UUID

/**
 * Message reads are public and revalidatable: the ETag / `If-None-Match` / `304`
 * handling is done by the [org.springframework.web.filter.ShallowEtagHeaderFilter]
 * registered in [dev.stackverse.backend.config.WebConfig]; controllers only add
 * `Cache-Control: no-cache` (SPEC rule 10).
 */
@RestController
@RequestMapping("/api/v1/messages")
class MessageController(
    private val repository: MessageRepository,
    private val service: MessageService,
    private val languageResolver: LanguageResolver,
) {

    @GetMapping
    fun list(
        @RequestParam key: String?,
        @RequestParam q: String?,
        @RequestParam language: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<PageResponse<MessageResponse>> {
        requireValidPaging(page, size)
        requireMaxLength(q, 200, "q")
        val qLike = q?.takeIf { it.isNotBlank() }?.let { "%${escapeLike(it.lowercase())}%" }
        val result = repository.search(key, qLike, language, Pageable.ofSize(size).withPage(page))
        return withNoCache(PageResponse.of(result) { MessageResponse.of(it) })
    }

    @GetMapping("/bundle")
    fun bundle(
        @RequestParam lang: String?,
        @RequestHeader(HttpHeaders.ACCEPT_LANGUAGE, required = false) acceptLanguage: String?,
    ): ResponseEntity<MessageBundleResponse> {
        val language = languageResolver.resolve(lang, acceptLanguage)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .header(HttpHeaders.CONTENT_LANGUAGE, language)
            .body(MessageBundleResponse(language, service.bundle(language)))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<MessageResponse> {
        val message = repository.findById(id).orElseThrow { NotFoundProblem() }
        return withNoCache(MessageResponse.of(message))
    }

    @PostMapping
    @PreAuthorize("hasRole('admin')")
    fun create(@RequestBody request: MessageRequest, authentication: Authentication): ResponseEntity<MessageResponse> {
        val message = service.create(authentication.name, request)
        val location = UriComponentsBuilder.fromPath("/api/v1/messages/{id}").build(message.id)
        return ResponseEntity.created(location).body(MessageResponse.of(message))
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: MessageRequest,
        authentication: Authentication,
    ): MessageResponse = MessageResponse.of(service.update(authentication.name, id, request))

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID, authentication: Authentication) {
        service.delete(authentication.name, id)
    }

    private fun <T : Any> withNoCache(body: T): ResponseEntity<T> =
        ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(body)

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
