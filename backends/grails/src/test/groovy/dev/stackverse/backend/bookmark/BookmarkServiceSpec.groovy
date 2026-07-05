package dev.stackverse.backend.bookmark

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import spock.lang.Specification

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

    private static class FakeJdbcTemplate extends JdbcTemplate {
        List<Map> bookmarks = []
        List<Map<String, Object>> tagRows = []
        int tagQueryCount = 0
        String tagSql
        List tagArgs = []

        @Override
        <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            2L as T
        }

        @Override
        <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
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
