package dev.stackverse.backend.message

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "messages")
class Message(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "key")
    var key: String,
    var language: String,
    @Column(name = "text")
    var text: String,
    var description: String?,
    val createdAt: Instant,
    var updatedAt: Instant,
)
