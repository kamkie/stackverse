package dev.stackverse.backend.message

import spock.lang.Specification

class MessageServiceSpec extends Specification {
    def "explicit language wins over Accept-Language when supported"() {
        given:
        MessageService service = service([en: [:], pl: [:]])

        expect:
        service.resolveLanguage("pl", "en;q=1.0") == "pl"
    }

    def "Accept-Language resolves by quality and falls back to English"() {
        given:
        MessageService service = service([en: [:], pl: [:]])

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
        MessageService service = service([
            en: ['ui.common.cancel': 'Cancel', 'ui.common.save': 'Save'],
            pl: ['ui.common.save': 'Zapisz', 'ui.extra': 'Dodatkowe']
        ])

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
        MessageService service = service([
            en: ['validation.url.required': 'URL is required.'],
            pl: ['language.marker': 'Polski']
        ])

        expect:
        service.validationMessage("validation.url.required", "pl", null) == "URL is required."
        service.validationMessage("validation.unknown", "pl", null) == "validation.unknown"
    }

    private static MessageService service(Map<String, Map<String, String>> messages) {
        new InMemoryMessageService(messages: messages)
    }

    private static class InMemoryMessageService extends MessageService {
        Map<String, Map<String, String>> messages

        @Override
        protected String lookup(String key, String language) {
            messages[language]?.get(key)
        }

        @Override
        protected Set<String> supportedLanguages() {
            messages.keySet()
        }

        @Override
        protected Map<String, String> messagesForLanguage(String language) {
            messages[language] ?: [:]
        }
    }
}
