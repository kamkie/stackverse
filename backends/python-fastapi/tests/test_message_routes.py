from __future__ import annotations

from fakes import ScriptedConnection, Step, message_row, scripted_transaction
from fastapi.testclient import TestClient
from psycopg.errors import UniqueViolation

from stackverse_backend import main
from stackverse_backend.auth import Caller, authenticate_request
from stackverse_backend.routers import messages


def client_for(caller: Caller | None) -> TestClient:
    app = main.build_app()
    app.dependency_overrides[authenticate_request] = lambda: caller
    return TestClient(app)


def test_public_message_reads_filter_cache_and_resolve_bundle_language(monkeypatch) -> None:
    row = message_row()
    calls = []

    def fake_query(statement, params=()):
        calls.append((statement.as_string(None), params))
        return [row]

    monkeypatch.setattr(messages, "query", fake_query)
    monkeypatch.setattr(messages, "one", lambda *_args: {"count": 1})
    client = client_for(None)

    listed = client.get("/api/v1/messages?key=ui.example&language=en&q=exam%25&page=1&size=5")
    cached = client.get(
        "/api/v1/messages?key=ui.example&language=en&q=exam%25&page=1&size=5",
        headers={"If-None-Match": listed.headers["ETag"]},
    )

    assert listed.status_code == 200
    assert listed.json()["items"][0]["key"] == "ui.example"
    assert cached.status_code == 304
    assert calls[0][1] == ("ui.example", "en", r"%exam\%%", r"%exam\%%", 5, 5)

    monkeypatch.setattr(messages, "resolve_language", lambda explicit, accepted: explicit or accepted.split("-")[0])
    monkeypatch.setattr(messages, "message_bundle", lambda language: {"ui.example": f"Example:{language}"})
    bundle = client.get("/api/v1/messages/bundle", headers={"Accept-Language": "pl-PL"})

    assert bundle.status_code == 200
    assert bundle.headers["Content-Language"] == "pl"
    assert bundle.json() == {"language": "pl", "messages": {"ui.example": "Example:pl"}}


def test_message_write_routes_enforce_admin_and_record_audit_without_logging_text(monkeypatch) -> None:
    admin = client_for(Caller("admin", ["admin", "moderator"]))
    regular = client_for(Caller("demo", []))
    payload = {"key": "ui.example", "language": "en", "text": "Secret-looking client text"}

    assert regular.post("/api/v1/messages", json=payload).status_code == 403

    audits = []
    events = []
    monkeypatch.setattr(messages, "record_audit", lambda *args: audits.append(args))
    monkeypatch.setattr(messages, "log_message_event", lambda *args: events.append(args))

    created_row = message_row(text=payload["text"])
    create_connection = ScriptedConnection(
        Step("select 1 from messages", one=None),
        Step("insert into messages", one=created_row),
    )
    monkeypatch.setattr(messages, "transaction", lambda: scripted_transaction(create_connection))
    created = admin.post("/api/v1/messages", json=payload)

    assert created.status_code == 201
    assert created.headers["Location"].startswith("/api/v1/messages/")
    assert audits[-1][2:5] == ("message.created", "message", created_row["id"])
    assert events[-1][:3] == ("message_created", "Message created", "admin")
    assert payload["text"] not in events[-1]
    create_connection.assert_exhausted()

    updated_row = message_row(text="Updated")
    update_connection = ScriptedConnection(
        Step("select 1 from messages where id", one={"exists": 1}),
        Step("select 1 from messages where key", one=None),
        Step("update messages", one=updated_row),
    )
    monkeypatch.setattr(messages, "transaction", lambda: scripted_transaction(update_connection))
    updated = admin.put(
        f"/api/v1/messages/{updated_row['id']}",
        json={"key": "ui.example", "language": "en", "text": "Updated"},
    )

    assert updated.status_code == 200
    assert updated.json()["text"] == "Updated"
    assert audits[-1][2] == "message.updated"
    update_connection.assert_exhausted()

    delete_connection = ScriptedConnection(Step("delete from messages", one=updated_row))
    monkeypatch.setattr(messages, "transaction", lambda: scripted_transaction(delete_connection))
    assert admin.delete(f"/api/v1/messages/{updated_row['id']}").status_code == 204
    assert audits[-1][2] == "message.deleted"
    assert events[-1][0] == "message_deleted"
    delete_connection.assert_exhausted()


def test_message_duplicate_checks_and_database_races_map_to_conflict(monkeypatch) -> None:
    client = client_for(Caller("admin", ["admin"]))
    payload = {"key": "ui.duplicate", "language": "en", "text": "Duplicate"}

    duplicate_connection = ScriptedConnection(Step("select 1 from messages", one={"exists": 1}))
    monkeypatch.setattr(messages, "transaction", lambda: scripted_transaction(duplicate_connection))
    duplicate = client.post("/api/v1/messages", json=payload)

    assert duplicate.status_code == 409
    assert "ui.duplicate" in duplicate.json()["detail"]
    duplicate_connection.assert_exhausted()

    race_connection = ScriptedConnection(
        Step("select 1 from messages", one=None),
        Step("insert into messages", raises=UniqueViolation("duplicate key")),
    )
    monkeypatch.setattr(messages, "transaction", lambda: scripted_transaction(race_connection))
    raced = client.post("/api/v1/messages", json=payload)

    assert raced.status_code == 409
    assert "already exists" in raced.json()["detail"]
    race_connection.assert_exhausted()


def test_message_get_and_delete_mask_missing_rows(monkeypatch) -> None:
    public = client_for(None)
    monkeypatch.setattr(messages, "one", lambda *_args: None)
    assert public.get("/api/v1/messages/00000000-0000-0000-0000-000000000099").status_code == 404

    missing_connection = ScriptedConnection(Step("delete from messages", one=None))
    monkeypatch.setattr(messages, "transaction", lambda: scripted_transaction(missing_connection))
    admin = client_for(Caller("admin", ["admin"]))
    assert admin.delete("/api/v1/messages/00000000-0000-0000-0000-000000000099").status_code == 404
    missing_connection.assert_exhausted()
