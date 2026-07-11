package dev.stackverse.backend.bookmark

import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.BookmarkCursor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import spock.lang.Specification

import java.sql.Timestamp
import java.time.Instant

class BookmarkServiceSpec extends Specification {
    def "offset listings hydrate tags with one batch query for the returned page"() {
        given:
        UUID first = UUID.fromString("11111111-1111-1111-1111-111111111111")
        UUID second = UUID.fromString("22222222-2222-2222-2222-222222222222")
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate(
            bookmarks: [bookmark(first), bookmark(second)],
            tagRows: [
                [bookmark_id: first, tag: "grails"],
                [bookmark_id: first, tag: "jdbc"],
                [bookmark_id: second, tag: "api"]
            ]
        )
        BookmarkService service = new BookmarkService(jdbcTemplate: jdbcTemplate)

        when:
        Map page = service.listOffset([:], "demo", null, null)

        then:
        page.items*.id == [first.toString(), second.toString()]
        page.items*.tags == [["grails", "jdbc"], ["api"]]
        jdbcTemplate.tagQueryCount == 1
        jdbcTemplate.tagArgs == [first, second]
        jdbcTemplate.tagSql.contains("where bookmark_id in (?, ?)")
    }

    def "visibility masking allows owners and public active readers only"() {
        given:
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        VisibleBookmarkService service = new VisibleBookmarkService(value: value)

        when:
        Map visible = service.getVisible(id, username)

        then:
        visible == value

        where:
        value                                                                    | username
        bookmark(UUID.randomUUID()) + [owner: 'alice', visibility: 'private']    | 'alice'
        bookmark(UUID.randomUUID()) + [owner: 'alice', visibility: 'public']     | 'bob'
    }

    def "visibility masking returns not found for absent private or hidden resources"() {
        given:
        UUID id = UUID.randomUUID()
        VisibleBookmarkService service = new VisibleBookmarkService(value: value)

        when:
        service.getVisible(id, 'bob')

        then:
        ApiError error = thrown()
        error.status == 404

        where:
        value << [
            null,
            bookmark(UUID.randomUUID()) + [owner: 'alice', visibility: 'private'],
            bookmark(UUID.randomUUID()) + [owner: 'alice', visibility: 'public', status: 'hidden']
        ]
    }

    def "public listings parameterize active visibility search and tag filters"() {
        given:
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate(bookmarks: [])
        BookmarkService service = new BookmarkService(jdbcTemplate: jdbcTemplate)

        when:
        Map page = service.listOffset([
            visibility: 'public',
            q         : '100%_MATCH',
            tags      : ['grails']
        ], null, null, null)

        then:
        page.items == []
        jdbcTemplate.countSql.contains("b.visibility = 'public' and b.status = 'active'")
        jdbcTemplate.countSql.contains("lower(b.title) like ? escape '\\'")
        jdbcTemplate.countSql.contains('exists (select 1 from bookmark_tags bt0')
        jdbcTemplate.countArgs == ['%100\\%\\_match%', '%100\\%\\_match%', 'grails']
        jdbcTemplate.mainArgs[-2..-1] == [20, 0]
    }

    def "private listings require a caller and reject invalid filters before querying"() {
        given:
        MessageService messages = Stub() {
            validationError(_, _, _, _) >> { String field, String key, String explicit, String accepted ->
                [field: field, messageKey: key, message: key]
            }
        }
        BookmarkService service = new BookmarkService(
            jdbcTemplate: new FakeJdbcTemplate(),
            messageService: messages
        )

        when:
        service.listOffset(params, username, null, null)

        then:
        ApiError error = thrown()
        error.status == status
        error.errors?.first()?.field == errorField

        where:
        params                                      | username || status | errorField
        [tags: [], visibility: null]                | null     || 401    | null
        [tags: [], visibility: 'friends']           | 'demo'   || 400    | null
        [tags: ['not valid'], visibility: 'public'] | null     || 400    | 'tag'
    }

    def "cursor listings fetch one extra row and emit a stable continuation cursor"() {
        given:
        UUID first = UUID.fromString('11111111-1111-1111-1111-111111111111')
        UUID second = UUID.fromString('22222222-2222-2222-2222-222222222222')
        UUID third = UUID.fromString('33333333-3333-3333-3333-333333333333')
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate(
            bookmarks: [bookmark(first), bookmark(second), bookmark(third)]
        )
        BookmarkService service = new BookmarkService(jdbcTemplate: jdbcTemplate)

        when:
        Map page = service.listCursor([size: '2', tags: []], 'demo', null, null)
        BookmarkCursor cursor = BookmarkCursor.decode(page.nextCursor as String)

        then:
        page.items*.id == [first.toString(), second.toString()]
        cursor.id == second
        cursor.createdAt == Instant.parse('2026-07-05T00:00:00Z')
        jdbcTemplate.mainArgs[-1] == 3
        jdbcTemplate.mainSql.contains('b.owner = ?')
    }

    def "supplied cursors become parameterized timestamp and id keysets"() {
        given:
        Instant createdAt = Instant.parse('2026-07-05T00:00:00Z')
        UUID id = UUID.fromString('11111111-1111-1111-1111-111111111111')
        String cursor = new BookmarkCursor(createdAt, id).encode()
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate(bookmarks: [])
        BookmarkService service = new BookmarkService(jdbcTemplate: jdbcTemplate)

        when:
        Map page = service.listCursor([size: '20', cursor: cursor, tags: []], 'demo', null, null)

        then:
        page == [items: [], nextCursor: null]
        jdbcTemplate.mainSql.contains('(b.created_at < ? or (b.created_at = ? and b.id < ?))')
        jdbcTemplate.mainArgs == ['demo', Timestamp.from(createdAt), Timestamp.from(createdAt), id, 21]
    }

    private static Map bookmark(UUID id) {
        [
            id        : id.toString(),
            url       : "https://example.com/${id}",
            title     : "Example",
            notes     : null,
            tags      : [],
            visibility: "private",
            status    : "active",
            owner     : "demo",
            createdAt : "2026-07-05T00:00:00Z",
            updatedAt : "2026-07-05T00:00:00Z"
        ]
    }

    private static class VisibleBookmarkService extends BookmarkService {
        Map value

        @Override
        Map find(UUID id) {
            value
        }
    }

    private static class FakeJdbcTemplate extends JdbcTemplate {
        List<Map> bookmarks = []
        List<Map<String, Object>> tagRows = []
        int tagQueryCount = 0
        String tagSql
        List tagArgs = []
        String countSql
        List countArgs = []
        String mainSql
        List mainArgs = []

        @Override
        <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            countSql = sql
            countArgs = args.toList()
            2L as T
        }

        @Override
        <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            mainSql = sql
            mainArgs = args.toList()
            bookmarks as List<T>
        }

        @Override
        List<Map<String, Object>> queryForList(String sql, Object... args) {
            tagQueryCount++
            tagSql = sql
            tagArgs = args.toList()
            tagRows
        }
    }
}
