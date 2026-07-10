package dev.stackverse.backend.persistence

import grails.testing.gorm.DataTest
import spock.lang.Specification

import java.time.Instant

class DomainDataSpec extends Specification implements DataTest {
    @Override
    Class<?>[] getDomainClassesToMock() {
        [Bookmark, Message, Report, UserAccount] as Class<?>[]
    }

    void 'DataTest registers every application domain and supports GORM queries'() {
        given:
        Instant now = Instant.parse('2026-01-01T00:00:00Z')

        when:
        new UserAccount(
            username: 'alice',
            firstSeen: now,
            lastSeen: now,
            status: 'active'
        ).save(failOnError: true, flush: true)

        then:
        [Bookmark, Message, Report, UserAccount].every {
            datastore.mappingContext.getPersistentEntity(it.name)
        }
        UserAccount.get('alice').status == 'active'
        UserAccount.countByStatus('active') == 1
    }
}
