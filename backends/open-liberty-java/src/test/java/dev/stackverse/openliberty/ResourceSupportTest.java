package dev.stackverse.openliberty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;

class ResourceSupportTest {
    private final TestResource resource = new TestResource();

    @Test
    void bookmarkInputNormalizesTagsAndDefaultsVisibility() {
        BookmarkInput input =
                resource.bookmark(
                        """
        {
          "url": " https://example.com/article ",
          "title": "  Example article  ",
          "notes": "Reference notes",
          "tags": [" Java ", "java", "OPEN-LIBERTY"]
        }
        """);

        assertEquals("https://example.com/article", input.url());
        assertEquals("Example article", input.title());
        assertEquals("Reference notes", input.notes());
        assertEquals(List.of("java", "open-liberty"), input.tags());
        assertEquals("private", input.visibility());
    }

    @Test
    void bookmarkInputCollectsContractValidationViolations() {
        ValidationProblem problem =
                assertThrows(
                        ValidationProblem.class,
                        () ->
                                resource.bookmark(
                                        """
        {
          "url": "ftp://example.com",
          "title": "",
          "notes": "%s",
          "tags": ["ok", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "bad_tag"]
        }
        """
                                                .formatted("n".repeat(4001))));

        assertEquals(
                Set.of(
                        new FieldViolation("url", "validation.url.invalid"),
                        new FieldViolation("title", "validation.title.required"),
                        new FieldViolation("notes", "validation.notes.too-long"),
                        new FieldViolation("tags", "validation.tags.too-many"),
                        new FieldViolation("tags", "validation.tag.invalid")),
                Set.copyOf(problem.violations));
    }

    @Test
    void bookmarkInputRejectsUnknownVisibility() {
        ApiProblem problem =
                assertThrows(
                        ApiProblem.class,
                        () ->
                                resource.bookmark(
                                        """
        {
          "url": "https://example.com",
          "title": "Example",
          "visibility": "friends"
        }
        """));

        assertEquals(400, problem.status);
        assertEquals("unknown visibility: friends", problem.detail);
    }

    @Test
    void listingWhereBuildsPublicFeedFiltersWithoutCaller() {
        QueryParts parts =
                resource.where(
                        null, new ListFilters(List.of("java", "api"), "100%_match\\", "public"));

        assertEquals(
                "visibility = 'public' and status = 'active' and tags @> ?::text[] "
                        + "and (title ilike ? escape '\\' or notes ilike ? escape '\\')",
                parts.where());
        assertArrayEquals(new String[] {"java", "api"}, (String[]) parts.params().get(0));
        assertEquals("%100\\%\\_match\\\\%", parts.params().get(1));
        assertEquals("%100\\%\\_match\\\\%", parts.params().get(2));
    }

    @Test
    void listingWhereRequiresCallerForNonPublicFeed() {
        ApiProblem anonymousProblem =
                assertThrows(
                        ApiProblem.class,
                        () -> resource.where(null, new ListFilters(List.of(), null, null)));

        assertEquals(401, anonymousProblem.status);

        QueryParts ownerParts =
                resource.where(
                        new Caller("alice", List.of(), null, null),
                        new ListFilters(List.of(), "", "private"));

        assertEquals("owner = ? and visibility = ?", ownerParts.where());
        assertEquals(List.of("alice", "private"), ownerParts.params());
    }

    @Test
    void validateTagsNormalizesAndRejectsInvalidSlugs() {
        assertEquals(
                List.of("java", "open-liberty"), resource.tags(List.of(" Java ", "OPEN-LIBERTY")));

        ValidationProblem problem =
                assertThrows(
                        ValidationProblem.class, () -> resource.tags(List.of("valid", "bad_tag")));

        assertEquals(
                List.of(new FieldViolation("tag", "validation.tag.invalid")), problem.violations);
    }

    @Test
    void messageInputValidatesRuntimeManagedMessages() {
        MessageInput input =
                resource.message(
                        """
        {
          "key": "validation.title.required",
          "language": "en",
          "text": "Title is required",
          "description": null
        }
        """);

        assertEquals("validation.title.required", input.key());
        assertEquals("en", input.language());
        assertEquals("Title is required", input.text());
        assertNull(input.description());
        assertEquals(
                Map.of(
                        "key",
                        "validation.title.required",
                        "language",
                        "en",
                        "text",
                        "Title is required"),
                input.toMap());
    }

    @Test
    void messageInputCollectsValidationViolations() {
        ValidationProblem problem =
                assertThrows(
                        ValidationProblem.class,
                        () ->
                                resource.message(
                                        """
        {
          "key": "Invalid Key",
          "language": "eng",
          "text": "",
          "description": "%s"
        }
        """
                                                .formatted("d".repeat(1001))));

        assertEquals(
                Set.of(
                        new FieldViolation("key", "validation.message.key.invalid"),
                        new FieldViolation("language", "validation.message.language.invalid"),
                        new FieldViolation("text", "validation.message.text.required"),
                        new FieldViolation(
                                "description", "validation.message.description.too-long")),
                Set.copyOf(problem.violations));
    }

    @Test
    void reportInputAcceptsKnownReasonsAndValidatesComment() {
        ReportInput input =
                resource.report(
                        """
        {
          "reason": "broken-link",
          "comment": "The target returns 404"
        }
        """);

        assertEquals("broken-link", input.reason());
        assertEquals("The target returns 404", input.comment());

        ValidationProblem problem =
                assertThrows(
                        ValidationProblem.class,
                        () ->
                                resource.report(
                                        """
        {
          "reason": "duplicate",
          "comment": "%s"
        }
        """
                                                .formatted("c".repeat(1001))));

        assertEquals(
                Set.of(
                        new FieldViolation("reason", "validation.report.reason.invalid"),
                        new FieldViolation("comment", "validation.report.comment.too-long")),
                Set.copyOf(problem.violations));
    }

    @Test
    void visibilityAllowsOwnersAndPublicActiveBookmarksOnly() {
        Caller owner = new Caller("alice", List.of(), null, null);
        Caller other = new Caller("bob", List.of(), null, null);
        ApiModels.Bookmark privateBookmark = bookmark("alice", "private", "active");
        ApiModels.Bookmark publicBookmark = bookmark("alice", "public", "active");
        ApiModels.Bookmark hiddenPublicBookmark = bookmark("alice", "public", "hidden");

        assertTrue(ResourceSupport.visibleTo(privateBookmark, owner));
        assertFalse(ResourceSupport.visibleTo(privateBookmark, other));
        assertTrue(ResourceSupport.visibleTo(publicBookmark, null));
        assertFalse(ResourceSupport.visibleTo(hiddenPublicBookmark, null));
        assertTrue(ResourceSupport.visibleTo(hiddenPublicBookmark, owner));
    }

    @Test
    void cursorsRoundTripAndRejectMalformedValues() {
        Instant createdAt = Instant.parse("2026-07-05T12:30:00Z");
        UUID id = UUID.fromString("019f33af-a3be-75d0-9f50-3fce1139c8c5");

        Cursor cursor = ResourceSupport.decodeCursor(ResourceSupport.encodeCursor(createdAt, id));

        assertEquals(createdAt, cursor.createdAt());
        assertEquals(id, cursor.id());

        ApiProblem problem =
                assertThrows(ApiProblem.class, () -> ResourceSupport.decodeCursor("not-a-cursor"));
        assertEquals(400, problem.status);
        assertEquals("The cursor is malformed or unresolvable.", problem.detail);
    }

    @Test
    void staticHelpersPreserveContractEdgeCases() {
        assertEquals("open", ResourceSupport.reportStatus(null, true));
        assertNull(ResourceSupport.reportStatus(null, false));
        assertEquals("dismissed", ResourceSupport.reportStatus("dismissed", true));
        assertEquals(
                UUID.fromString("019f33af-a3be-75d0-9f50-3fce1139c8c5"),
                ResourceSupport.uuid("019f33af-a3be-75d0-9f50-3fce1139c8c5"));
        assertEquals(42, ResourceSupport.integer("42", 10, "size"));
        assertEquals(10, ResourceSupport.integer("", 10, "size"));
        assertEquals("100\\%\\_match\\\\", ResourceSupport.escapeLike("100%_match\\"));
        assertEquals(
                Instant.parse("2026-07-05T12:30:00Z"),
                ResourceSupport.parseInstant("2026-07-05T12:30:00Z", "from"));
    }

    @Test
    void staticHelpersTranslateInvalidInputToProblemResponses() {
        assertEquals(
                404,
                assertThrows(ApiProblem.class, () -> ResourceSupport.uuid("not-a-uuid")).status);
        assertEquals(
                400,
                assertThrows(ApiProblem.class, () -> ResourceSupport.reportStatus("closed", true))
                        .status);
        assertEquals(
                400,
                assertThrows(ApiProblem.class, () -> ResourceSupport.integer("NaN", 20, "size"))
                        .status);
        assertEquals(
                400,
                assertThrows(ApiProblem.class, () -> ResourceSupport.requireMax("abcd", 3, "q"))
                        .status);
        assertEquals(
                400,
                assertThrows(
                                ApiProblem.class,
                                () -> ResourceSupport.parseInstant("2026-07-05", "from"))
                        .status);
    }

    @Test
    void pageAndLinkedHelpersShapeResponseBodies() {
        ApiModels.Page page = ResourceSupport.page(List.of("a", "b"), new Paging(1, 2), 5);

        assertEquals(List.of("a", "b"), page.items());
        assertEquals(1, page.page());
        assertEquals(2, page.size());
        assertEquals(5L, page.totalItems());
        assertEquals(3L, page.totalPages());
        assertEquals(0L, ResourceSupport.page(List.of(), new Paging(0, 20), 0).totalPages());
        assertEquals(
                List.of("first", "second", 3),
                ResourceSupport.append(List.of("first"), "second", 3));
        assertEquals(
                Map.of("present", "value"),
                ResourceSupport.linked("present", "value", "missing", null));
    }

    @Test
    void callerReportsRolesExactlyFromJwtClaims() {
        Caller caller =
                new Caller("moderator", List.of("moderator"), "Mod User", "mod@example.com");

        assertEquals("moderator", caller.username());
        assertTrue(caller.hasRole("moderator"));
        assertFalse(caller.hasRole("admin"));
    }

    @Test
    void validationProblemCopiesViolationList() {
        List<FieldViolation> source = new java.util.ArrayList<>();
        source.add(new FieldViolation("field", "validation.key"));

        ValidationProblem problem = new ValidationProblem(source);
        source.add(new FieldViolation("other", "validation.other"));

        assertEquals(List.of(new FieldViolation("field", "validation.key")), problem.violations);
    }

    private static ApiModels.Bookmark bookmark(String owner, String visibility, String status) {
        return new ApiModels.Bookmark(
                UUID.randomUUID().toString(),
                "https://example.com",
                "title",
                null,
                List.of(),
                visibility,
                status,
                owner,
                Instant.EPOCH.toString(),
                Instant.EPOCH.toString());
    }

    private static final class TestResource extends ResourceSupport {
        TestResource() {
            beanValidator =
                    Validation.byDefaultProvider()
                            .configure()
                            .messageInterpolator(new ParameterMessageInterpolator())
                            .buildValidatorFactory()
                            .getValidator();
        }

        BookmarkInput bookmark(String body) {
            return bookmarkInput(read(body, BookmarkInput.class));
        }

        MessageInput message(String body) {
            return validateDto(read(body, MessageInput.class));
        }

        ReportInput report(String body) {
            return validateDto(read(body, ReportInput.class));
        }

        QueryParts where(Caller caller, ListFilters filters) {
            return listingWhere(caller, filters);
        }

        List<String> tags(List<String> raw) {
            return validateTags(raw, "tag");
        }

        private static <T> T read(String body, Class<T> type) {
            try {
                return JsonSupport.MAPPER.readValue(body, type);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        }
    }
}
