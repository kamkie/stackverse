package dev.stackverse.backend.persistence

import grails.gorm.annotation.Entity

import java.time.Instant

@Entity
class Bookmark {
    UUID id
    String owner
    String url
    String title
    String notes
    String visibility
    String status
    Instant createdAt
    Instant updatedAt

    static constraints = {
        owner blank: false
        url blank: false, maxSize: 2000
        title blank: false, maxSize: 200
        notes nullable: true, maxSize: 4000
        visibility inList: ['private', 'public']
        status inList: ['active', 'hidden']
        createdAt nullable: false
        updatedAt nullable: false
    }

    static mapping = {
        table 'bookmarks'
        id generator: 'assigned'
        version false
        createdAt column: 'created_at'
        updatedAt column: 'updated_at'
    }
}
