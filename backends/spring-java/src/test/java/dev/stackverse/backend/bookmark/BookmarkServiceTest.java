package dev.stackverse.backend.bookmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.stackverse.backend.common.ConflictProblem;
import dev.stackverse.backend.common.FieldViolation;
import dev.stackverse.backend.common.NotFoundProblem;
import dev.stackverse.backend.common.ValidationProblem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

class BookmarkServiceTest {
    private final BookmarkRepository repository = mock(BookmarkRepository.class);
    private final BookmarkService service = new BookmarkService(repository);

    @Test
    void createNormalizesInputAndDefaultsPrivateVisibility() {
        when(repository.save(any(Bookmark.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bookmark created = service.create(
            "alice",
            new BookmarkRequest(
                "  https://example.com/path  ",
                "  Example  ",
                "notes",
                List.of(" Java ", "java", "spring-boot"),
                null
            )
        );

        assertThat(created.getOwner()).isEqualTo("alice");
        assertThat(created.getUrl()).isEqualTo("https://example.com/path");
        assertThat(created.getTitle()).isEqualTo("Example");
        assertThat(created.getNotes()).isEqualTo("notes");
        assertThat(created.getTags()).containsExactly("java", "spring-boot");
        assertThat(created.getVisibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(created.getStatus()).isEqualTo(BookmarkStatus.ACTIVE);
        assertThat(created.getCreatedAt()).isEqualTo(created.getUpdatedAt());
        verify(repository).save(created);
    }

    @Test
    void createReportsAllInvalidFieldsBeforePersistence() {
        BookmarkRequest request = new BookmarkRequest(
            "ftp://example.com",
            " ",
            "n".repeat(4001),
            List.of("bad_tag", "tag-2", "tag-3", "tag-4", "tag-5", "tag-6", "tag-7", "tag-8", "tag-9", "tag-10", "tag-11"),
            Visibility.PUBLIC
        );

        ValidationProblem problem = catchThrowableOfType(ValidationProblem.class, () -> service.create("alice", request));

        assertThat(problem.getViolations())
            .extracting(FieldViolation::field, FieldViolation::messageKey)
            .contains(
                org.assertj.core.groups.Tuple.tuple("url", "validation.url.invalid"),
                org.assertj.core.groups.Tuple.tuple("title", "validation.title.required"),
                org.assertj.core.groups.Tuple.tuple("notes", "validation.notes.too-long"),
                org.assertj.core.groups.Tuple.tuple("tags", "validation.tags.too-many"),
                org.assertj.core.groups.Tuple.tuple("tags", "validation.tag.invalid")
            );
        verifyNoInteractions(repository);
    }

    @Test
    void getAppliesOwnerAndPublicVisibilityRules() {
        Bookmark privateBookmark = bookmark("alice", Visibility.PRIVATE, BookmarkStatus.ACTIVE);
        Bookmark hiddenBookmark = bookmark("alice", Visibility.PUBLIC, BookmarkStatus.HIDDEN);
        Bookmark publicBookmark = bookmark("alice", Visibility.PUBLIC, BookmarkStatus.ACTIVE);

        when(repository.findById(privateBookmark.getId())).thenReturn(Optional.of(privateBookmark));
        when(repository.findById(hiddenBookmark.getId())).thenReturn(Optional.of(hiddenBookmark));
        when(repository.findById(publicBookmark.getId())).thenReturn(Optional.of(publicBookmark));

        assertThat(service.get("alice", privateBookmark.getId())).isSameAs(privateBookmark);
        assertThat(service.get("alice", hiddenBookmark.getId())).isSameAs(hiddenBookmark);
        assertThat(service.get("bob", publicBookmark.getId())).isSameAs(publicBookmark);
        assertThatThrownBy(() -> service.get("bob", privateBookmark.getId())).isInstanceOf(NotFoundProblem.class);
        assertThatThrownBy(() -> service.get("bob", hiddenBookmark.getId())).isInstanceOf(NotFoundProblem.class);
    }

    @Test
    void getMasksMissingBookmarks() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("alice", id)).isInstanceOf(NotFoundProblem.class);
    }

    @Test
    void updateUsesLockedOwnedRowAndMutatesEditableFields() {
        Bookmark bookmark = bookmark("alice", Visibility.PRIVATE, BookmarkStatus.ACTIVE);
        when(repository.findForUpdateById(bookmark.getId())).thenReturn(bookmark);

        Bookmark updated = service.update(
            "alice",
            bookmark.getId(),
            new BookmarkRequest("https://updated.example", " Updated ", null, List.of(" Java ", "java"), Visibility.PUBLIC)
        );

        assertThat(updated).isSameAs(bookmark);
        assertThat(updated.getUrl()).isEqualTo("https://updated.example");
        assertThat(updated.getTitle()).isEqualTo("Updated");
        assertThat(updated.getNotes()).isNull();
        assertThat(updated.getTags()).containsExactly("java");
        assertThat(updated.getVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(updated.getStatus()).isEqualTo(BookmarkStatus.ACTIVE);
        assertThat(updated.getUpdatedAt()).isAfter(Instant.EPOCH);
        verify(repository).findForUpdateById(bookmark.getId());
    }

    @Test
    void updateMasksMissingAndForeignBookmarks() {
        UUID missing = UUID.randomUUID();
        when(repository.findForUpdateById(missing)).thenReturn(null);
        Bookmark foreign = bookmark("alice", Visibility.PRIVATE, BookmarkStatus.ACTIVE);
        when(repository.findForUpdateById(foreign.getId())).thenReturn(foreign);
        BookmarkRequest request = validRequest(Visibility.PRIVATE);

        assertThatThrownBy(() -> service.update("alice", missing, request)).isInstanceOf(NotFoundProblem.class);
        assertThatThrownBy(() -> service.update("bob", foreign.getId(), request)).isInstanceOf(NotFoundProblem.class);
    }

    @Test
    void hiddenBookmarkCannotBeRepublishedByOwner() {
        Bookmark hidden = bookmark("alice", Visibility.PRIVATE, BookmarkStatus.HIDDEN);
        String originalUrl = hidden.getUrl();
        String originalTitle = hidden.getTitle();
        String originalNotes = hidden.getNotes();
        Set<String> originalTags = Set.copyOf(hidden.getTags());
        Instant originalUpdatedAt = hidden.getUpdatedAt();
        when(repository.findForUpdateById(hidden.getId())).thenReturn(hidden);
        BookmarkRequest changedRequest = new BookmarkRequest(
            "https://changed.example",
            "Changed title",
            "changed notes",
            List.of("spring"),
            Visibility.PUBLIC
        );

        ConflictProblem problem = catchThrowableOfType(
            ConflictProblem.class,
            () -> service.update("alice", hidden.getId(), changedRequest)
        );

        assertThat(problem.getDetailKey()).isEqualTo("error.bookmark.hidden-publish");
        assertThat(hidden.getUrl()).isEqualTo(originalUrl);
        assertThat(hidden.getTitle()).isEqualTo(originalTitle);
        assertThat(hidden.getNotes()).isEqualTo(originalNotes);
        assertThat(hidden.getTags()).containsExactlyInAnyOrderElementsOf(originalTags);
        assertThat(hidden.getVisibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(hidden.getUpdatedAt()).isEqualTo(originalUpdatedAt);
    }

    @Test
    void deleteRequiresOwnership() {
        Bookmark owned = bookmark("alice", Visibility.PRIVATE, BookmarkStatus.ACTIVE);
        Bookmark foreign = bookmark("bob", Visibility.PRIVATE, BookmarkStatus.ACTIVE);
        when(repository.findById(owned.getId())).thenReturn(Optional.of(owned));
        when(repository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        service.delete("alice", owned.getId());

        verify(repository).delete(owned);
        assertThatThrownBy(() -> service.delete("alice", foreign.getId())).isInstanceOf(NotFoundProblem.class);
        verify(repository, never()).delete(foreign);
    }

    @Test
    void listOffsetBuildsNewestFirstPageRequest() {
        Bookmark bookmark = bookmark("alice", Visibility.PRIVATE, BookmarkStatus.ACTIVE);
        when(repository.findAll(org.mockito.ArgumentMatchers.<Specification<Bookmark>>any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(bookmark)));

        var page = service.listOffset("alice", new BookmarkListQuery(List.of(), null, null), 2, 15);

        assertThat(page.getContent()).containsExactly(bookmark);
        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(org.mockito.ArgumentMatchers.<Specification<Bookmark>>any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(15);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
        assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    @Test
    void queryTagsAreNormalizedAndValidated() {
        assertThat(BookmarkService.validateQueryTags(List.of(" Java ", "spring-boot")))
            .containsExactly("java", "spring-boot");

        ValidationProblem problem = catchThrowableOfType(
            ValidationProblem.class,
            () -> BookmarkService.validateQueryTags(List.of("bad_tag"))
        );
        assertThat(problem.getViolations())
            .extracting(FieldViolation::field, FieldViolation::messageKey)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("tag", "validation.tag.invalid"));
    }

    private static BookmarkRequest validRequest(Visibility visibility) {
        return new BookmarkRequest("https://example.com", "Example", null, List.of("java"), visibility);
    }

    private static Bookmark bookmark(String owner, Visibility visibility, BookmarkStatus status) {
        return new Bookmark(
            owner,
            "https://example.com",
            "Example",
            "notes",
            Set.of("java"),
            visibility,
            status,
            Instant.EPOCH,
            Instant.EPOCH
        );
    }
}
