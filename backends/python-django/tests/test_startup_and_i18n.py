import json
from types import SimpleNamespace

import pytest

from stackverse_api import startup
from stackverse_api.i18n import localize, localize_many, message_bundle, resolve_language, supported_languages
from stackverse_api.models import Message

pytestmark = pytest.mark.django_db


def test_message_seed_is_idempotent_and_preserves_runtime_edits(tmp_path, monkeypatch) -> None:
    (tmp_path / "en.json").write_text(json.dumps({"ui.greeting": "Hello", "ui.fallback": "Fallback"}), encoding="utf-8")
    (tmp_path / "pl.json").write_text(json.dumps({"ui.greeting": "Czesc"}), encoding="utf-8")
    monkeypatch.setattr(startup, "config", SimpleNamespace(seed_messages_dir=tmp_path))
    events: list[tuple[tuple, dict]] = []
    monkeypatch.setattr(startup, "log_event", lambda *args, **fields: events.append((args, fields)))

    startup.seed_messages()
    greeting = Message.objects.get(key="ui.greeting", language="en")
    greeting.text = "Runtime edit"
    greeting.save(update_fields=["text"])
    startup.seed_messages()

    greeting.refresh_from_db()
    assert greeting.text == "Runtime edit"
    assert Message.objects.count() == 3
    assert [(args[1], fields["language"], fields["inserted"], fields["skipped"]) for args, fields in events] == [
        ("message_seed_imported", "en", 2, 0),
        ("message_seed_imported", "pl", 1, 0),
        ("message_seed_imported", "en", 0, 2),
        ("message_seed_imported", "pl", 0, 1),
    ]

    empty = tmp_path / "empty"
    empty.mkdir()
    monkeypatch.setattr(startup, "config", SimpleNamespace(seed_messages_dir=empty))
    with pytest.raises(RuntimeError, match="Message seed directory not found"):
        startup.seed_messages()


def test_database_backed_language_resolution_fallback_and_bundle(message_factory) -> None:
    message_factory("ui.greeting", "en", "Hello")
    message_factory("ui.greeting", "pl", "Czesc")
    message_factory("ui.only-en", "en", "English only")

    assert supported_languages() == {"en", "pl"}
    assert resolve_language("pl", "en;q=1") == "pl"
    assert resolve_language("zz", "zz;q=1, pl-PL;q=0.8, en;q=0.5") == "pl"
    assert resolve_language(None, "zz") == "en"
    assert localize("ui.greeting", "pl") == "Czesc"
    assert localize_many(["ui.only-en", "ui.greeting", "ui.only-en", "missing.key"], "pl") == {
        "ui.only-en": "English only",
        "ui.greeting": "Czesc",
        "missing.key": "missing.key",
    }
    assert message_bundle("pl") == {"ui.greeting": "Czesc", "ui.only-en": "English only"}
