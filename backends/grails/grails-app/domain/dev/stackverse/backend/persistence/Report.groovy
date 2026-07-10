package dev.stackverse.backend.persistence

import grails.gorm.annotation.Entity

import java.time.Instant

@Entity
class Report {
    UUID id
    UUID bookmarkId
    String reporter
    String reason
    String comment
    String status
    String resolvedBy
    Instant resolvedAt
    String resolutionNote
    Instant createdAt

    static constraints = {
        bookmarkId nullable: false
        reporter blank: false
        reason inList: ['spam', 'offensive', 'broken-link', 'other']
        comment nullable: true, maxSize: 1000
        status inList: ['open', 'dismissed', 'actioned']
        resolvedBy nullable: true
        resolvedAt nullable: true
        resolutionNote nullable: true, maxSize: 1000
        createdAt nullable: false
    }

    static mapping = {
        table 'reports'
        id generator: 'assigned'
        version false
        bookmarkId column: 'bookmark_id'
        resolvedBy column: 'resolved_by'
        resolvedAt column: 'resolved_at'
        resolutionNote column: 'resolution_note'
        createdAt column: 'created_at'
    }
}
