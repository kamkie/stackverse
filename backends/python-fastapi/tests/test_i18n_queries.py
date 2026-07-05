from stackverse_backend import i18n


def test_supported_languages_reads_distinct_languages(monkeypatch) -> None:
    monkeypatch.setattr(i18n, "query", lambda *_args: [{"language": "en"}, {"language": "pl"}])

    assert i18n.supported_languages() == {"en", "pl"}


def test_resolve_language_prefers_explicit_then_accept_language_then_default(monkeypatch) -> None:
    monkeypatch.setattr(i18n, "supported_languages", lambda: {"en", "pl"})

    assert i18n.resolve_language("pl", "en;q=1") == "pl"
    assert i18n.resolve_language("zz", "fr;q=0.9, pl-PL;q=0.8") == "pl"
    assert i18n.resolve_language(None, "zz;q=1") == "en"


def test_localize_falls_back_to_key_when_message_is_missing(monkeypatch) -> None:
    calls = []

    def fake_query(sql, params):
        calls.append((sql, params))
        return [{"text": "Wymagany tytul"}]

    monkeypatch.setattr(i18n, "query", fake_query)

    assert i18n.localize("validation.title.required", "pl") == "Wymagany tytul"
    assert calls[0][1] == ("validation.title.required", ["pl", "en"], "pl")

    monkeypatch.setattr(i18n, "query", lambda *_args: [])
    assert i18n.localize("validation.unknown", "pl") == "validation.unknown"


def test_message_bundle_uses_requested_language_and_english_fallback(monkeypatch) -> None:
    monkeypatch.setattr(
        i18n,
        "query",
        lambda *_args: [
            {"key": "ui.nav.home", "language": "pl", "text": "Start"},
            {"key": "ui.nav.home", "language": "en", "text": "Home"},
            {"key": "ui.nav.search", "language": "en", "text": "Search"},
        ],
    )

    assert i18n.message_bundle("pl") == {
        "ui.nav.home": "Start",
        "ui.nav.search": "Search",
    }
