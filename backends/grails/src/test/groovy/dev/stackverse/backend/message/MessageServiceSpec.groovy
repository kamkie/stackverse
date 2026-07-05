package dev.stackverse.backend.message

import org.springframework.jdbc.core.JdbcTemplate
import spock.lang.Specification

class MessageServiceSpec extends Specification {
    def "explicit language wins over Accept-Language when supported"() {
        given:
        MessageService service = new MessageService(jdbcTemplate: languageStore(["en", "pl"]))

        expect:
        service.resolveLanguage("pl", "en;q=1.0") == "pl"
    }

    def "Accept-Language resolves by quality and falls back to English"() {
        given:
        MessageService service = new MessageService(jdbcTemplate: languageStore(["en", "pl"]))

        expect:
        service.resolveLanguage(null, header) == language

        where:
        header                                || language
        "fr-CA, pl-PL;q=0.9, en;q=0.4"      || "pl"
        "fr-CA, de;q=0.8"                   || "en"
        "pl;q=not-a-number, en;q=0.8"       || "en"
    }

    def "bundle merges requested language over English fallback messages"() {
        given:
        JdbcTemplate jdbcTemplate = Stub()
        jdbcTemplate.queryForList("select distinct language from messages", String) >> ["en", "pl"]
        jdbcTemplate.queryForList("select key, text from messages where language = ? order by key asc", "en") >> [
            [key: "ui.common.cancel", text: "Cancel"],
            [key: "ui.common.save", text: "Save"]
        ]
        jdbcTemplate.queryForList("select key, text from messages where language = ? order by key asc", "pl") >> [
            [key: "ui.common.save", text: "Zapisz"],
            [key: "ui.extra", text: "Dodatkowe"]
        ]
        MessageService service = new MessageService(jdbcTemplate: jdbcTemplate)

        expect:
        service.bundle("pl", null) == [
            language: "pl",
            messages: [
                "ui.common.cancel": "Cancel",
                "ui.common.save"  : "Zapisz",
                "ui.extra"        : "Dodatkowe"
            ]
        ]
    }

    def "validation message falls back from requested language to English then key"() {
        given:
        JdbcTemplate jdbcTemplate = Stub()
        jdbcTemplate.queryForList("select distinct language from messages", String) >> ["en", "pl"]
        jdbcTemplate.queryForList("select text from messages where key = ? and language = ?", String, "validation.url.required", "pl") >> []
        jdbcTemplate.queryForList("select text from messages where key = ? and language = ?", String, "validation.url.required", "en") >> ["URL is required."]
        jdbcTemplate.queryForList("select text from messages where key = ? and language = ?", String, "validation.unknown", "pl") >> []
        jdbcTemplate.queryForList("select text from messages where key = ? and language = ?", String, "validation.unknown", "en") >> []
        MessageService service = new MessageService(jdbcTemplate: jdbcTemplate)

        expect:
        service.validationMessage("validation.url.required", "pl", null) == "URL is required."
        service.validationMessage("validation.unknown", "pl", null) == "validation.unknown"
    }

    private JdbcTemplate languageStore(List<String> languages) {
        JdbcTemplate jdbcTemplate = Stub()
        jdbcTemplate.queryForList("select distinct language from messages", String) >> languages
        jdbcTemplate
    }
}
