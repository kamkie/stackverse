package dev.stackverse.backend.persistence

import grails.gorm.annotation.Entity

import java.time.Instant

@Entity
class Message {
    UUID id
    String key
    String language
    String text
    String description
    Instant createdAt
    Instant updatedAt

    static constraints = {
        key blank: false, maxSize: 150
        language matches: /^[a-z]{2}$/
        text blank: false, maxSize: 2000
        description nullable: true, maxSize: 1000
        createdAt nullable: false
        updatedAt nullable: false
    }

    static mapping = {
        table 'messages'
        id generator: 'assigned'
        version false
        createdAt column: 'created_at'
        updatedAt column: 'updated_at'
    }
}
