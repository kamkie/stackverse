package dev.stackverse.backend.bookmark

import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class Visibility(@get:JsonValue val wire: String) {
    PRIVATE("private"),
    PUBLIC("public"),
}

enum class BookmarkStatus(@get:JsonValue val wire: String) {
    ACTIVE("active"),
    HIDDEN("hidden"),
}

@Entity
@Table(name = "bookmarks")
class Bookmark(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "owner")
    val owner: String,
    var url: String,
    var title: String,
    var notes: String?,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "bookmark_tags", joinColumns = [JoinColumn(name = "bookmark_id")])
    @Column(name = "tag")
    var tags: MutableSet<String>,
    @Enumerated(EnumType.STRING)
    var visibility: Visibility,
    @Enumerated(EnumType.STRING)
    var status: BookmarkStatus,
    val createdAt: Instant,
    var updatedAt: Instant,
)
