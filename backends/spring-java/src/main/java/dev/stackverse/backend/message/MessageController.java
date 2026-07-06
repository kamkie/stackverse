package dev.stackverse.backend.message;

import static dev.stackverse.backend.common.RequestValidation.requireMaxLength;
import static dev.stackverse.backend.common.RequestValidation.requireValidPaging;

import dev.stackverse.backend.common.NotFoundProblem;
import dev.stackverse.backend.common.PageResponse;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {
    private final MessageRepository repository;
    private final MessageService service;
    private final LanguageResolver languageResolver;

    public MessageController(MessageRepository repository, MessageService service, LanguageResolver languageResolver) {
        this.repository = repository;
        this.service = service;
        this.languageResolver = languageResolver;
    }

    @GetMapping
    public ResponseEntity<PageResponse<MessageResponse>> list(
        @RequestParam(name = "key", required = false) String key,
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "language", required = false) String language,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        requireValidPaging(page, size);
        requireMaxLength(q, 200, "q");
        String qLike = q == null || q.isBlank() ? null : "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
        return withNoCache(PageResponse.of(repository.search(key, qLike, language, Pageable.ofSize(size).withPage(page)), MessageResponse::of));
    }

    @GetMapping("/bundle")
    public ResponseEntity<MessageBundleResponse> bundle(
        @RequestParam(name = "lang", required = false) String lang,
        @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage
    ) {
        String language = languageResolver.resolve(lang, acceptLanguage);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .header(HttpHeaders.CONTENT_LANGUAGE, language)
            .body(new MessageBundleResponse(language, service.bundle(language)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MessageResponse> get(@PathVariable("id") UUID id) {
        Message message = repository.findById(id).orElseThrow(NotFoundProblem::new);
        return withNoCache(MessageResponse.of(message));
    }

    @PostMapping
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<MessageResponse> create(@RequestBody MessageRequest request, Authentication authentication) {
        Message message = service.create(authentication.getName(), request);
        var location = UriComponentsBuilder.fromPath("/api/v1/messages/{id}").build(message.getId());
        return ResponseEntity.created(location).body(MessageResponse.of(message));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    public MessageResponse update(@PathVariable("id") UUID id, @RequestBody MessageRequest request, Authentication authentication) {
        return MessageResponse.of(service.update(authentication.getName(), id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") UUID id, Authentication authentication) {
        service.delete(authentication.getName(), id);
    }

    private static <T> ResponseEntity<T> withNoCache(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(body);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
