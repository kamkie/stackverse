package dev.stackverse.backend.bookmark;

import static dev.stackverse.backend.common.Time.nowUtc;

import dev.stackverse.backend.common.ConflictProblem;
import dev.stackverse.backend.common.NotFoundProblem;
import dev.stackverse.backend.common.UnauthorizedProblem;
import dev.stackverse.backend.common.Validator;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BookmarkService {
    private static final Pattern TAG_PATTERN = Pattern.compile("^[a-z0-9-]{1,30}$");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final BookmarkRepository repository;

    public BookmarkService(BookmarkRepository repository) {
        this.repository = repository;
    }

    public Bookmark create(String owner, BookmarkRequest request) {
        ValidatedBookmark input = validate(request);
        Instant now = nowUtc();
        return repository.save(new Bookmark(
            owner,
            input.url(),
            input.title(),
            input.notes(),
            input.tags(),
            input.visibility(),
            BookmarkStatus.ACTIVE,
            now,
            now
        ));
    }

    @Transactional(readOnly = true)
    public Bookmark get(String caller, UUID id) {
        Bookmark bookmark = repository.findById(id).orElseThrow(NotFoundProblem::new);
        if (!isVisibleTo(bookmark, caller)) {
            throw new NotFoundProblem();
        }
        return bookmark;
    }

    public Bookmark update(String caller, UUID id, BookmarkRequest request) {
        Bookmark bookmark = repository.findForUpdateById(id);
        if (bookmark == null) {
            throw new NotFoundProblem();
        }
        if (!bookmark.getOwner().equals(caller)) {
            throw new NotFoundProblem();
        }
        ValidatedBookmark input = validate(request);
        if (bookmark.getStatus() == BookmarkStatus.HIDDEN && input.visibility() == Visibility.PUBLIC) {
            throw new ConflictProblem(
                "This bookmark was hidden by moderation and cannot be made public.",
                "error.bookmark.hidden-publish"
            );
        }
        bookmark.setUrl(input.url());
        bookmark.setTitle(input.title());
        bookmark.setNotes(input.notes());
        bookmark.setTags(input.tags());
        bookmark.setVisibility(input.visibility());
        bookmark.setUpdatedAt(nowUtc());
        return bookmark;
    }

    public void delete(String caller, UUID id) {
        repository.delete(ownedByCaller(caller, id));
    }

    @Transactional(readOnly = true)
    public Page<Bookmark> listOffset(String caller, BookmarkListQuery query, int page, int size) {
        return repository.findAll(specificationFor(caller, query), PageRequest.of(page, size, NEWEST_FIRST));
    }

    @Transactional(readOnly = true)
    public BookmarkSlice listKeyset(String caller, BookmarkListQuery query, BookmarkCursor cursor, int size) {
        Specification<Bookmark> specification = specificationFor(caller, query);
        if (cursor != null) {
            specification = specification.and(before(cursor));
        }
        List<Bookmark> fetched = repository.findBy(specification, fluent ->
            fluent.sortBy(NEWEST_FIRST).limit(size + 1).all()
        );
        List<Bookmark> items = fetched.stream().limit(size).toList();
        BookmarkCursor nextCursor = fetched.size() > size ? BookmarkCursor.of(items.getLast()) : null;
        return new BookmarkSlice(items, nextCursor);
    }

    public static List<String> validateQueryTags(List<String> tags) {
        List<String> normalized = tags.stream().map(BookmarkService::normalizeTag).toList();
        Validator validator = new Validator();
        validator.check(normalized.stream().allMatch(tag -> TAG_PATTERN.matcher(tag).matches()), "tag", "validation.tag.invalid");
        validator.throwIfInvalid();
        return normalized;
    }

    private static boolean isVisibleTo(Bookmark bookmark, String caller) {
        return bookmark.getOwner().equals(caller)
            || (bookmark.getVisibility() == Visibility.PUBLIC && bookmark.getStatus() == BookmarkStatus.ACTIVE);
    }

    private Bookmark ownedByCaller(String caller, UUID id) {
        Bookmark bookmark = repository.findById(id).orElseThrow(NotFoundProblem::new);
        if (!bookmark.getOwner().equals(caller)) {
            throw new NotFoundProblem();
        }
        return bookmark;
    }

    private Specification<Bookmark> specificationFor(String caller, BookmarkListQuery query) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.visibility() == Visibility.PUBLIC) {
                predicates.add(cb.equal(root.get("visibility"), Visibility.PUBLIC));
                predicates.add(cb.equal(root.get("status"), BookmarkStatus.ACTIVE));
            } else {
                if (caller == null) {
                    throw new UnauthorizedProblem();
                }
                predicates.add(cb.equal(root.get("owner"), caller));
                if (query.visibility() != null) {
                    predicates.add(cb.equal(root.get("visibility"), query.visibility()));
                }
            }
            for (String tag : query.tags()) {
                if (criteriaQuery != null) {
                    criteriaQuery.distinct(true);
                }
                Join<Bookmark, String> tagJoin = root.join("tags");
                predicates.add(cb.equal(tagJoin, tag));
            }
            if (query.q() != null && !query.q().isBlank()) {
                String pattern = "%" + escapeLike(query.q().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.get("notes")), pattern, '\\')
                ));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Specification<Bookmark> before(BookmarkCursor cursor) {
        return (root, query, cb) -> cb.or(
            cb.lessThan(root.get("createdAt"), cursor.createdAt()),
            cb.and(
                cb.equal(root.get("createdAt"), cursor.createdAt()),
                cb.lessThan(root.get("id"), cursor.id())
            )
        );
    }

    private ValidatedBookmark validate(BookmarkRequest request) {
        Validator validator = new Validator();

        String url = trimToEmpty(request.url());
        if (url.isEmpty()) {
            validator.reject("url", "validation.url.required");
        } else {
            validator.check(url.length() <= 2000 && isHttpUrl(url), "url", "validation.url.invalid");
        }

        String title = trimToEmpty(request.title());
        validator.check(!title.isEmpty(), "title", "validation.title.required");
        validator.check(title.length() <= 200, "title", "validation.title.too-long");
        validator.check(request.notes() == null || request.notes().length() <= 4000, "notes", "validation.notes.too-long");

        Set<String> tags = new LinkedHashSet<>();
        if (request.tags() != null) {
            request.tags().stream().map(BookmarkService::normalizeTag).forEach(tags::add);
        }
        validator.check(tags.size() <= 10, "tags", "validation.tags.too-many");
        validator.check(tags.stream().allMatch(tag -> TAG_PATTERN.matcher(tag).matches()), "tags", "validation.tag.invalid");

        validator.throwIfInvalid();
        return new ValidatedBookmark(url, title, request.notes(), tags, request.visibility() == null ? Visibility.PRIVATE : request.visibility());
    }

    private static String normalizeTag(String tag) {
        return tag.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean isHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            return uri.isAbsolute()
                && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && uri.getHost() != null
                && !uri.getHost().isBlank();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private record ValidatedBookmark(String url, String title, String notes, Set<String> tags, Visibility visibility) {
    }
}
