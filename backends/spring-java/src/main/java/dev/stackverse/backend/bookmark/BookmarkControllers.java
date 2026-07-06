package dev.stackverse.backend.bookmark;

import static dev.stackverse.backend.bookmark.BookmarkService.validateQueryTags;
import static dev.stackverse.backend.common.RequestValidation.requireMaxLength;
import static dev.stackverse.backend.common.RequestValidation.requireValidPaging;

import dev.stackverse.backend.common.PageResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/bookmarks")
class BookmarkV1Controller {
    private final BookmarkService service;

    BookmarkV1Controller(BookmarkService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<BookmarkResponse> list(
        @RequestParam(name = "tag", required = false) List<String> tag,
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "visibility", required = false) Visibility visibility,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size,
        Authentication authentication
    ) {
        requireValidPaging(page, size);
        requireMaxLength(q, 200, "q");
        String caller = authentication == null ? null : authentication.getName();
        return PageResponse.of(
            service.listOffset(caller, new BookmarkListQuery(validateQueryTags(tag == null ? List.of() : tag), q, visibility), page, size),
            BookmarkResponse::of
        );
    }

    @PostMapping
    ResponseEntity<BookmarkResponse> create(@RequestBody BookmarkRequest request, Authentication authentication) {
        Bookmark bookmark = service.create(authentication.getName(), request);
        var location = UriComponentsBuilder.fromPath("/api/v1/bookmarks/{id}").build(bookmark.getId());
        return ResponseEntity.created(location).body(BookmarkResponse.of(bookmark));
    }

    @GetMapping("/{id}")
    BookmarkResponse get(@PathVariable("id") UUID id, Authentication authentication) {
        return BookmarkResponse.of(service.get(authentication == null ? null : authentication.getName(), id));
    }

    @PutMapping("/{id}")
    BookmarkResponse update(@PathVariable("id") UUID id, @RequestBody BookmarkRequest request, Authentication authentication) {
        return BookmarkResponse.of(service.update(authentication.getName(), id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable("id") UUID id, Authentication authentication) {
        service.delete(authentication.getName(), id);
    }
}

@RestController
class BookmarkV2Controller {
    private final BookmarkService service;

    BookmarkV2Controller(BookmarkService service) {
        this.service = service;
    }

    @GetMapping("/api/v2/bookmarks")
    BookmarkCursorPageResponse list(
        @RequestParam(name = "tag", required = false) List<String> tag,
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "visibility", required = false) Visibility visibility,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "size", defaultValue = "20") int size,
        Authentication authentication
    ) {
        requireValidPaging(0, size);
        requireMaxLength(q, 200, "q");
        String caller = authentication == null ? null : authentication.getName();
        BookmarkSlice slice = service.listKeyset(
            caller,
            new BookmarkListQuery(validateQueryTags(tag == null ? List.of() : tag), q, visibility),
            cursor == null ? null : BookmarkCursor.decode(cursor),
            size
        );
        return new BookmarkCursorPageResponse(
            slice.items().stream().map(BookmarkResponse::of).toList(),
            slice.nextCursor() == null ? null : slice.nextCursor().encode()
        );
    }
}

@RestController
class TagController {
    private final BookmarkRepository repository;

    TagController(BookmarkRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/v1/tags")
    TagListResponse list(Authentication authentication) {
        return new TagListResponse(
            repository.countTagsByOwner(authentication.getName()).stream()
                .map(row -> new TagCountResponse(row.getTag(), row.getCount()))
                .toList()
        );
    }
}
