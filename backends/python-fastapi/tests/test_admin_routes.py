from __future__ import annotations

from datetime import UTC, datetime

from fakes import ScriptedConnection, Step, account_row, audit_row, bookmark_row, scripted_transaction
from fastapi.testclient import TestClient

from stackverse_backend import main
from stackverse_backend.auth import Caller, authenticate_request
from stackverse_backend.routers import admin


def client_for(caller: Caller | None) -> TestClient:
    app = main.build_app()
    app.dependency_overrides[authenticate_request] = lambda: caller
    return TestClient(app)


def admin_client() -> TestClient:
    return client_for(Caller("admin", ["admin", "moderator"]))


def test_bookmark_moderation_status_change_uses_one_transaction_boundary_with_audit_and_log(monkeypatch) -> None:
    original = bookmark_row(status="active")
    hidden = bookmark_row(status="hidden")
    connection = ScriptedConnection(
        Step("select * from bookmarks", one=original),
        Step("update bookmarks set status", one=hidden),
    )
    monkeypatch.setattr(admin, "transaction", lambda: scripted_transaction(connection))
    audits = []
    events = []
    monkeypatch.setattr(admin, "record_audit", lambda *args: audits.append(args))
    monkeypatch.setattr(admin, "log_event", lambda *args, **fields: events.append((args, fields)))

    response = admin_client().put(
        f"/api/v1/admin/bookmarks/{original['id']}/status",
        json={"status": "hidden", "note": "confirmed abuse"},
    )

    assert response.status_code == 200
    assert response.json()["status"] == "hidden"
    assert audits[0][2:5] == ("bookmark.status-changed", "bookmark", original["id"])
    assert audits[0][5] == {"from": "active", "to": "hidden", "note": "confirmed abuse"}
    assert events[0][0][:3] == ("info", "bookmark_status_changed", "success")
    assert "confirmed abuse" not in str(events[0])
    connection.assert_exhausted()


def test_admin_user_listing_lookup_and_status_transitions_enforce_business_rules(monkeypatch) -> None:
    active = account_row(username="target")
    calls = []
    monkeypatch.setattr(
        admin, "query", lambda statement, params: calls.append((statement.as_string(None), params)) or [active]
    )
    monkeypatch.setattr(admin, "one", lambda *_args: {"count": 1})
    monkeypatch.setattr(admin, "find_account", lambda username: active if username == "target" else None)
    client = admin_client()

    listed = client.get("/api/v1/admin/users?q=tar%25&status=active&page=1&size=5")
    found = client.get("/api/v1/admin/users/target")
    missing = client.get("/api/v1/admin/users/missing")

    assert listed.status_code == 200
    assert listed.json()["items"][0]["username"] == "target"
    assert calls[0][1] == (r"%tar\%%", "active", 5, 5)
    assert found.status_code == 200
    assert missing.status_code == 404
    assert client.get("/api/v1/admin/users?status=pending").status_code == 400

    self_block = client.put("/api/v1/admin/users/admin/status", json={"status": "blocked", "reason": "no"})
    assert self_block.status_code == 409

    monkeypatch.setattr(main, "resolve_language", lambda *_args: "en")
    monkeypatch.setattr(main, "localize", lambda key, _language: f"localized:{key}")
    no_reason = client.put("/api/v1/admin/users/target/status", json={"status": "blocked"})
    assert no_reason.status_code == 400
    assert no_reason.json()["errors"][0]["messageKey"] == "validation.block.reason.required"

    audits = []
    events = []
    monkeypatch.setattr(admin, "record_audit", lambda *args: audits.append(args))
    monkeypatch.setattr(admin, "log_event", lambda *args, **fields: events.append((args, fields)))
    blocked = account_row(username="target", status="blocked", blocked_reason="abuse")
    monkeypatch.setattr(admin, "find_account", lambda _username: blocked)
    block_connection = ScriptedConnection(
        Step("select username from user_accounts", one={"username": "target"}),
        Step("update user_accounts set status = 'blocked'"),
    )
    monkeypatch.setattr(admin, "transaction", lambda: scripted_transaction(block_connection))

    response = client.put("/api/v1/admin/users/target/status", json={"status": "blocked", "reason": "abuse"})

    assert response.status_code == 200
    assert response.json()["status"] == "blocked"
    assert audits[-1][2:5] == ("user.blocked", "user", "target")
    assert events[-1][0][1] == "user_blocked"
    assert "abuse" not in str(events[-1])
    block_connection.assert_exhausted()

    monkeypatch.setattr(admin, "find_account", lambda _username: active)
    unblock_connection = ScriptedConnection(
        Step("select username from user_accounts", one={"username": "target"}),
        Step("update user_accounts set status = 'active'"),
    )
    monkeypatch.setattr(admin, "transaction", lambda: scripted_transaction(unblock_connection))
    unblocked = client.put("/api/v1/admin/users/target/status", json={"status": "active"})

    assert unblocked.status_code == 200
    assert unblocked.json()["status"] == "active"
    assert audits[-1][2:5] == ("user.unblocked", "user", "target")
    assert events[-1][0][1] == "user_unblocked"
    unblock_connection.assert_exhausted()


def test_audit_log_filters_are_parameterized_and_map_optional_detail(monkeypatch) -> None:
    row = audit_row()
    calls = []

    def fake_query(statement, params):
        calls.append((statement.as_string(None), params))
        return [row]

    monkeypatch.setattr(admin, "query", fake_query)
    monkeypatch.setattr(admin, "one", lambda *_args: {"count": 1})
    response = admin_client().get(
        "/api/v1/admin/audit-log?actor=admin&action=user.blocked&targetType=user&targetId=demo"
        "&from=2026-07-01T00:00:00Z&to=2026-07-02T00:00:00Z&page=1&size=5"
    )

    assert response.status_code == 200
    assert response.json()["items"][0]["detail"] == {"reason": "abuse"}
    assert calls[0][1][:4] == ("admin", "user.blocked", "user", "demo")
    assert calls[0][1][-2:] == (5, 5)
    assert "created_at >= %s" in calls[0][0]
    assert "created_at <= %s" in calls[0][0]


def test_stats_zero_fill_thirty_utc_days_and_support_etag_revalidation(monkeypatch) -> None:
    class FixedDateTime(datetime):
        @classmethod
        def now(cls, tz=None):
            return cls(2026, 7, 11, 15, 30, tzinfo=tz or UTC)

    monkeypatch.setattr(admin, "datetime", FixedDateTime)

    def counts(table, _column, _start):
        return {"2026-06-12": 2, "2026-07-11": 3} if table == "bookmarks" else {"2026-07-11": 4}

    monkeypatch.setattr(admin, "count_per_day", counts)
    monkeypatch.setattr(
        admin,
        "one",
        lambda *_args: {
            "users": 5,
            "bookmarks": 6,
            "public_bookmarks": 4,
            "hidden_bookmarks": 1,
            "open_reports": 2,
        },
    )
    monkeypatch.setattr(admin, "query", lambda *_args: [{"tag": "python", "count": 3}])
    client = admin_client()

    response = client.get("/api/v1/admin/stats")
    cached = client.get("/api/v1/admin/stats", headers={"If-None-Match": response.headers["ETag"]})

    assert response.status_code == 200
    payload = response.json()
    assert len(payload["daily"]) == 30
    assert payload["daily"][0] == {"date": "2026-06-12", "bookmarksCreated": 2, "activeUsers": 0}
    assert payload["daily"][-1] == {"date": "2026-07-11", "bookmarksCreated": 3, "activeUsers": 4}
    assert payload["totals"]["openReports"] == 2
    assert payload["topTags"] == [{"tag": "python", "count": 3}]
    assert cached.status_code == 304
