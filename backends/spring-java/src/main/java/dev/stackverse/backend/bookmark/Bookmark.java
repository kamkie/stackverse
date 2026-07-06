package dev.stackverse.backend.bookmark;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "bookmarks")
public class Bookmark {
    @Id
    private UUID id;
    @Column(name = "owner")
    private String owner;
    private String url;
    private String title;
    private String notes;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "bookmark_tags", joinColumns = @JoinColumn(name = "bookmark_id"))
    @Column(name = "tag")
    private Set<String> tags = new LinkedHashSet<>();
    @Enumerated(EnumType.STRING)
    private Visibility visibility;
    @Enumerated(EnumType.STRING)
    private BookmarkStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    protected Bookmark() {
    }

    public Bookmark(String owner, String url, String title, String notes, Set<String> tags, Visibility visibility, BookmarkStatus status, Instant createdAt, Instant updatedAt) {
        this.id = UUID.randomUUID();
        this.owner = owner;
        this.url = url;
        this.title = title;
        this.notes = notes;
        this.tags = new LinkedHashSet<>(tags);
        this.visibility = visibility;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Set<String> getTags() {
        return tags;
    }

    public void setTags(Set<String> tags) {
        this.tags = new LinkedHashSet<>(tags);
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public void setVisibility(Visibility visibility) {
        this.visibility = visibility;
    }

    public BookmarkStatus getStatus() {
        return status;
    }

    public void setStatus(BookmarkStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
