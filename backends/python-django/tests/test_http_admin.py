from datetime import UTC, datetime, timedelta

import pytest

from stackverse_api import views
from stackverse_api.audit import record_audit
from stackverse_api.models import AuditEntry, Message
from stackverse_api.time import now_utc

pytestmark = pytest.mark.django_db


def test_message_reads_etags_language_fallback_and_admin_crud_are_audited_without_logging_text(
    api_client, client_for, message_factory, monkeypatch
) -> None:
    greeting_en = message_factory("ui.greeting", "en", "Hello")
    message_factory("ui.greeting", "pl", "Czesc")
    message_factory("ui.fallback", "en", "Fallback")

    page = api_client.get("/api/v1/messages", {"q": "greet"})
    assert page.status_code == 200
    assert page["Cache-Control"] == "no-cache"
    assert page.json()["totalItems"] == 2
    assert api_client.get("/api/v1/messages", {"q": "greet"}, HTTP_IF_NONE_MATCH=page["ETag"]).status_code == 304

    bundle = api_client.get("/api/v1/messages/bundle", {"lang": "pl"})
    assert bundle.status_code == 200
    assert bundle["Content-Language"] == "pl"
    assert bundle.json() == {
        "language": "pl",
        "messages": {"ui.fallback": "Fallback", "ui.greeting": "Czesc"},
    }

    detail = api_client.get(f"/api/v1/messages/{greeting_en.id}")
    assert detail.status_code == 200
    assert api_client.get(f"/api/v1/messages/{greeting_en.id}", HTTP_IF_NONE_MATCH=detail["ETag"]).status_code == 304

    payload = {
        "key": "ui.private-copy",
        "language": "en",
        "text": "Sensitive translation text",
        "description": "Translator-only context",
    }
    assert client_for("regular").post("/api/v1/messages", payload, format="json").status_code == 403

    events: list[tuple[tuple, dict]] = []
    monkeypatch.setattr("stackverse_api.views.log_event", lambda *args, **fields: events.append((args, fields)))
    admin = client_for("admin", ["admin", "moderator"])
    created = admin.post("/api/v1/messages", payload, format="json")
    assert created.status_code == 201
    message_id = created.json()["id"]
    assert created["Location"] == f"/api/v1/messages/{message_id}"
    assert admin.post("/api/v1/messages", payload, format="json").status_code == 409

    conflict = admin.put(
        f"/api/v1/messages/{message_id}",
        {"key": "ui.greeting", "language": "en", "text": "Duplicate"},
        format="json",
    )
    assert conflict.status_code == 409

    updated = admin.put(
        f"/api/v1/messages/{message_id}",
        {"key": "ui.private-copy", "language": "pl", "text": "Tajne tlumaczenie"},
        format="json",
    )
    assert updated.status_code == 200
    assert updated.json()["language"] == "pl"
    assert admin.delete(f"/api/v1/messages/{message_id}").status_code == 204
    assert not Message.objects.filter(id=message_id).exists()

    assert list(AuditEntry.objects.order_by("created_at").values_list("action", flat=True)) == [
        "message.created",
        "message.updated",
        "message.deleted",
    ]
    assert [args[1] for args, _fields in events] == ["message_created", "message_updated", "message_deleted"]
    assert "Sensitive translation text" not in repr(events)
    assert "Tajne tlumaczenie" not in repr(events)
    assert "Translator-only context" not in repr(events)


def test_admin_user_directory_blocking_and_unblocking_enforce_roles_validation_and_audit(
    client_for, account_factory, bookmark_factory
) -> None:
    account_factory("admin")
    account_factory("alice")
    account_factory("bob", status="blocked", blocked_reason="Existing reason")
    bookmark_factory(owner="alice")
    bookmark_factory(owner="alice")

    assert client_for("moderator", ["moderator"]).get("/api/v1/admin/users").status_code == 403
    admin = client_for("admin", ["admin", "moderator"])
    listing = admin.get("/api/v1/admin/users", {"q": "ali", "status": "active"})
    assert listing.status_code == 200
    assert listing.json()["totalItems"] == 1
    assert listing.json()["items"][0]["bookmarkCount"] == 2
    assert admin.get("/api/v1/admin/users/alice").json()["username"] == "alice"
    assert admin.get("/api/v1/admin/users/missing").status_code == 404

    missing_reason = admin.put("/api/v1/admin/users/alice/status", {"status": "blocked"}, format="json")
    assert missing_reason.status_code == 400
    assert missing_reason.json()["errors"][0]["messageKey"] == "validation.block.reason.required"
    assert (
        admin.put(
            "/api/v1/admin/users/admin/status",
            {"status": "blocked", "reason": "Self block"},
            format="json",
        ).status_code
        == 409
    )

    blocked = admin.put(
        "/api/v1/admin/users/alice/status",
        {"status": "blocked", "reason": "Policy violation"},
        format="json",
    )
    assert blocked.status_code == 200
    assert blocked.json()["status"] == "blocked"
    assert blocked.json()["blockedReason"] == "Policy violation"
    assert blocked.json()["bookmarkCount"] == 2

    unblocked = admin.put("/api/v1/admin/users/alice/status", {"status": "active"}, format="json")
    assert unblocked.status_code == 200
    assert unblocked.json()["status"] == "active"
    assert "blockedReason" not in unblocked.json()
    assert list(AuditEntry.objects.order_by("created_at").values_list("action", flat=True)) == [
        "user.blocked",
        "user.unblocked",
    ]

    assert admin.get("/api/v1/admin/users", {"status": "unknown"}).status_code == 400


def test_stats_zero_fill_aggregates_etag_and_audit_filters_use_real_postgresql_queries(
    api_client, client_for, account_factory, bookmark_factory, report_factory, monkeypatch
) -> None:
    now = now_utc()

    class FixedDateTime(datetime):
        @classmethod
        def now(cls, tz=None):
            assert tz is UTC
            return now

    monkeypatch.setattr(views, "datetime", FixedDateTime)
    account_factory("alice", last_seen=now - timedelta(days=1))
    account_factory("bob", last_seen=now)
    first = bookmark_factory(
        owner="alice",
        tags=["python", "django"],
        visibility="public",
        created_at=now - timedelta(days=1),
    )
    bookmark_factory(owner="bob", tags=["python"], status="hidden", created_at=now)
    report_factory(first, "bob")

    assert client_for("regular").get("/api/v1/admin/stats").status_code == 403
    moderator = client_for("moderator", ["moderator"])
    stats = moderator.get("/api/v1/admin/stats")
    assert stats.status_code == 200
    payload = stats.json()
    assert payload["totals"] == {
        "users": 2,
        "bookmarks": 2,
        "publicBookmarks": 1,
        "hiddenBookmarks": 1,
        "openReports": 1,
    }
    assert len(payload["daily"]) == 30
    assert payload["daily"][-1]["date"] == now.date().isoformat()
    assert payload["topTags"] == [{"tag": "python", "count": 2}, {"tag": "django", "count": 1}]
    assert moderator.get("/api/v1/admin/stats", HTTP_IF_NONE_MATCH=stats["ETag"]).status_code == 304

    record_audit("admin", "message.created", "message", "one", {"key": "ui.one"})
    record_audit("other-admin", "user.blocked", "user", "alice")
    assert api_client.get("/api/v1/admin/audit-log").status_code == 401
    admin = client_for("admin", ["admin", "moderator"])
    filtered = admin.get("/api/v1/admin/audit-log", {"actor": "admin", "action": "message.created"})
    assert filtered.status_code == 200
    assert filtered.json()["totalItems"] == 1
    assert filtered.json()["items"][0]["detail"] == {"key": "ui.one"}

    after = (now + timedelta(minutes=1)).isoformat().replace("+00:00", "Z")
    assert admin.get("/api/v1/admin/audit-log", {"to": after}).status_code == 200
    invalid_date = admin.get("/api/v1/admin/audit-log", {"from": "not-a-date"})
    assert invalid_date.status_code == 400
    assert "RFC 3339" in invalid_date.json()["detail"]
