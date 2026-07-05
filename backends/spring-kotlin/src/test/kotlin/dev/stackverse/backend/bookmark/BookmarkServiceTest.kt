package dev.stackverse.backend.bookmark

import dev.stackverse.backend.common.ConflictProblem
import dev.stackverse.backend.common.FieldViolation
import dev.stackverse.backend.common.NotFoundProblem
import dev.stackverse.backend.common.ValidationProblem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Optional
import java.util.UUID

class BookmarkServiceTest {

    private val repository = mock(BookmarkRepository::class.java)
    private val service = BookmarkService(repository)

    @Test
    fun `create normalizes tags trims required fields and defaults visibility to private`() {
        `when`(repository.save(any(Bookmark::class.java))).thenAnswer { invocation ->
            invocation.getArgument<Bookmark>(0)
        }

        val bookmark = service.create(
            "alice",
            BookmarkRequest(
                url = " https://example.com/bookmark ",
                title = " Kotlin reference ",
                notes = "Notes",
                tags = listOf(" Kotlin ", "kotlin", "spring-boot"),
                visibility = null,
            ),
        )

        assertThat(bookmark.owner).isEqualTo("alice")
        assertThat(bookmark.url).isEqualTo("https://example.com/bookmark")
        assertThat(bookmark.title).isEqualTo("Kotlin reference")
        assertThat(bookmark.tags).containsExactly("kotlin", "spring-boot")
        assertThat(bookmark.visibility).isEqualTo(Visibility.PRIVATE)
        assertThat(bookmark.status).isEqualTo(BookmarkStatus.ACTIVE)
        assertThat(bookmark.createdAt).isEqualTo(bookmark.updatedAt)
        verify(repository).save(any(Bookmark::class.java))
        verifyNoMoreInteractions(repository)
    }

    @Test
    fun `create reports all bookmark validation failures before touching repository`() {
        val problem = assertThrows<ValidationProblem> {
            service.create(
                "alice",
                BookmarkRequest(
                    url = "ftp://example.com",
                    title = " ",
                    notes = "x".repeat(4001),
                    tags = (1..11).map { "tag$it" } + "bad tag",
                ),
            )
        }

        assertThat(problem.violations).containsExactlyInAnyOrder(
            FieldViolation("url", "validation.url.invalid"),
            FieldViolation("title", "validation.title.required"),
            FieldViolation("notes", "validation.notes.too-long"),
            FieldViolation("tags", "validation.tags.too-many"),
            FieldViolation("tags", "validation.tag.invalid"),
        )
        verifyNoInteractions(repository)
    }

    @Test
    fun `update rejects republishing a moderation-hidden bookmark before mutating it`() {
        val id = UUID.randomUUID()
        val bookmark = bookmark(
            id = id,
            visibility = Visibility.PRIVATE,
            status = BookmarkStatus.HIDDEN,
        )
        `when`(repository.findForUpdateById(id)).thenReturn(bookmark)

        val problem = assertThrows<ConflictProblem> {
            service.update(
                "alice",
                id,
                BookmarkRequest(
                    url = "https://example.com/updated",
                    title = "Updated",
                    visibility = Visibility.PUBLIC,
                ),
            )
        }

        assertThat(problem.detailKey).isEqualTo("error.bookmark.hidden-publish")
        assertThat(bookmark.url).isEqualTo("https://example.com")
        assertThat(bookmark.title).isEqualTo("Example")
        assertThat(bookmark.visibility).isEqualTo(Visibility.PRIVATE)
        assertThat(bookmark.status).isEqualTo(BookmarkStatus.HIDDEN)
    }

    @Test
    fun `get masks private bookmarks from non-owners`() {
        val id = UUID.randomUUID()
        `when`(repository.findById(id)).thenReturn(Optional.of(bookmark(id = id, visibility = Visibility.PRIVATE)))

        assertThrows<NotFoundProblem> {
            service.get("bob", id)
        }
    }

    @Test
    fun `query tags are normalized and validated`() {
        assertThat(validateQueryTags(listOf(" Kotlin ", "spring-boot"))).containsExactly("kotlin", "spring-boot")

        val problem = assertThrows<ValidationProblem> {
            validateQueryTags(listOf("valid", "Bad Tag"))
        }
        assertThat(problem.violations).containsExactly(FieldViolation("tag", "validation.tag.invalid"))
    }

    private fun bookmark(
        id: UUID = UUID.randomUUID(),
        visibility: Visibility = Visibility.PUBLIC,
        status: BookmarkStatus = BookmarkStatus.ACTIVE,
        createdAt: Instant = Instant.parse("2026-07-05T12:00:00Z"),
    ) = Bookmark(
        id = id,
        owner = "alice",
        url = "https://example.com",
        title = "Example",
        notes = null,
        tags = mutableSetOf("kotlin"),
        visibility = visibility,
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
