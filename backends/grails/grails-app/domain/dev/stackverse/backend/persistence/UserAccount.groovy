package dev.stackverse.backend.persistence

import grails.gorm.annotation.Entity

import java.time.Instant

@Entity
class UserAccount {
    String username
    Instant firstSeen
    Instant lastSeen
    String status
    String blockedReason

    static constraints = {
        username blank: false
        firstSeen nullable: false
        lastSeen nullable: false
        status inList: ['active', 'blocked']
        blockedReason nullable: true, maxSize: 1000
    }

    static mapping = {
        table 'user_accounts'
        id name: 'username', generator: 'assigned'
        version false
        firstSeen column: 'first_seen'
        lastSeen column: 'last_seen'
        blockedReason column: 'blocked_reason'
    }
}
