from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.requests import Request

from stackverse_backend import api
from stackverse_backend.problems import BadRequestProblem, ConflictProblem, NotFoundProblem, UnauthorizedProblem, ValidationProblem


def make_request(query: str = "") -> Request:
    return Request(
        {
            "type": "http",
            "method": "GET",
            "path": "/api/v1/bookmarks",
            "headers": [],
            "query_string": query.encode("ascii"),
        }
    )


def timestamp() -> datetime:
    return datetime(2026, 7, 1, 12, 30, 5, 123456, UTC)


def test_response_mappers_omit_null_optional_fields_and_format_timestamps() -> None:
    bookmark = api.to_bookmark_response(
        {
            "id": UUID("00000000-0000-0000-0000-000000000001"),
            "url": "https://example.com",
            "title": "Example",
            "notes": None,
            "tags": None,
            "visibility": "private",
            "status": "active",
            "owner": "demo",
            "created_at": timestamp(),
            "updated_at": timestamp(),
        }
    )
    assert bookmark == {
        "id": "00000000-0000-0000-0000-000000000001",
        "url": "https://example.com",
        "title": "Example",
        "tags": [],
        "visibility": "private",
        "status": "active",
        "owner": "demo",
        "createdAt": "2026-07-01T12:30:05.123Z",
        "updatedAt": "2026-07-01T12:30:05.123Z",
    }

    report = api.to_report_response(
        {
            "id": UUID("00000000-0000-0000-0000-000000000002"),
            "bookmark_id": UUID("00000000-0000-0000-0000-000000000001"),
            "reporter": "demo",
            "reason": "spam",
            "comment": None,
            "status": "open",
            "created_at": timestamp(),
            "resolved_by": None,
            "resolved_at": None,
            "resolution_note": None,
        }
    )
    assert "comment" not in report
    assert "resolvedBy" not in report
    assert report["createdAt"] == "2026-07-01T12:30:05.123Z"

    message = api.to_message_response(
        {
            "id": UUID("00000000-0000-0000-0000-000000000003"),
            "key": "ui.example",
            "language": "en",
            "text": "Example",
            "description": None,
            "created_at": timestamp(),
            "updated_at": timestamp(),
        }
    )
    assert "description" not in message
    assert message["updatedAt"] == "2026-07-01T12:30:05.123Z"

    account = api.to_user_account_response(
        {
            "username": "demo",
            "first_seen": timestamp(),
            "last_seen": timestamp(),
            "status": "active",
            "blocked_reason": None,
            "bookmark_count": "7",
        }
    )
    assert account["bookmarkCount"] == 7
    assert "blockedReason" not in account

    audit = api.to_audit_response(
        {
            "id": UUID("00000000-0000-0000-0000-000000000004"),
            "actor": "admin",
            "action": "message.created",
            "target_type": "message",
            "target_id": "m1",
            "detail": None,
            "created_at": timestamp(),
        }
    )
    assert "detail" not in audit
    assert audit["targetType"] == "message"


def test_page_of_maps_rows_and_calculates_total_pages() -> None:
    assert api.page_of([{"value": 1}, {"value": 2}], page=2, size=2, total=5, mapper=lambda row: row["value"]) == {
        "items": [1, 2],
        "page": 2,
        "size": 2,
        "totalItems": 5,
        "totalPages": 3,
    }


def test_parse_bookmark_filters_and_listing_where_match_public_and_private_contracts() -> None:
    filters = api.parse_bookmark_filters(make_request("tag=Python&tag=fast-api&q=50%25_off&visibility=public"))
    assert filters == {"tags": ["python", "fast-api"], "q": "50%_off", "visibility": "public"}

    where, params = api.listing_where(None, filters)
    assert where == "visibility = 'public' and status = 'active' and tags @> %s::text[] and (title ilike %s escape E'\\\\' or notes ilike %s escape E'\\\\')"
    assert params == (["python", "fast-api"], r"%50\%\_off%", r"%50\%\_off%")

    private_where, private_params = api.listing_where(
        SimpleNamespace(username="demo"), {"tags": [], "q": None, "visibility": None}
    )
    assert private_where == "owner = %s"
    assert private_params == ("demo",)

    with pytest.raises(UnauthorizedProblem):
        api.listing_where(None, {"tags": [], "q": None, "visibility": None})
    with pytest.raises(BadRequestProblem, match="unknown visibility"):
        api.parse_bookmark_filters(make_request("visibility=shared"))


def test_ownership_visibility_and_report_state_helpers() -> None:
    owner_bookmark = {"owner": "demo", "visibility": "private", "status": "hidden"}
    public_bookmark = {"owner": "other", "visibility": "public", "status": "active"}
    hidden_public_bookmark = {"owner": "other", "visibility": "public", "status": "hidden"}

    assert api.visible_to(owner_bookmark, "demo")
    assert api.visible_to(public_bookmark, None)
    assert not api.visible_to(hidden_public_bookmark, "demo")

    api.require_open({"status": "open"})
    with pytest.raises(ConflictProblem):
        api.require_open({"status": "dismissed"})

    assert api.validated_report_status(None) is None
    assert api.validated_report_status("actioned") == "actioned"
    with pytest.raises(BadRequestProblem, match="unknown status"):
        api.validated_report_status("closed")


def test_validation_helpers_cover_reports_resolutions_and_bookmark_statuses() -> None:
    assert api.validate_report_input({"reason": "broken-link", "comment": "Dead link"}) == {
        "reason": "broken-link",
        "comment": "Dead link",
    }
    with pytest.raises(ValidationProblem) as report_error:
        api.validate_report_input({"reason": "duplicate", "comment": "x" * 1001})
    assert {(violation.field, violation.message_key) for violation in report_error.value.violations} == {
        ("reason", "validation.report.reason.invalid"),
        ("comment", "validation.report.comment.too-long"),
    }

    assert api.validate_resolution_input({"resolution": "open", "note": "reopen"}) == ("open", "reopen")
    with pytest.raises(ValidationProblem):
        api.validate_resolution_input({"resolution": "closed"})

    assert api.validate_bookmark_status_input({"status": "hidden", "note": "moderation"}) == ("hidden", "moderation")
    with pytest.raises(ValidationProblem):
        api.validate_bookmark_status_input({"status": "deleted"})


def test_owned_by_caller_and_date_helpers(monkeypatch) -> None:
    monkeypatch.setattr(api, "find_bookmark", lambda bookmark_id: {"id": bookmark_id, "owner": "demo"})
    assert api.owned_by_caller("demo", "bookmark-id")["id"] == "bookmark-id"

    with pytest.raises(NotFoundProblem):
        api.owned_by_caller("other", "bookmark-id")

    parsed = api.date_param("2026-07-01T12:30:05Z", "from")
    assert parsed == datetime(2026, 7, 1, 12, 30, 5, tzinfo=UTC)
    assert api.date_param(None, "from") is None
    with pytest.raises(BadRequestProblem, match="from must be an RFC 3339 date-time"):
        api.date_param("not-a-date", "from")


def test_swap_readiness_only_reports_state_transitions(monkeypatch) -> None:
    monkeypatch.setattr(api, "_was_ready", True)

    assert not api.swap_readiness(True)
    assert api.swap_readiness(False)
    assert not api.swap_readiness(False)
    assert api.swap_readiness(True)


def test_message_conflict_snapshot_and_log_event(monkeypatch) -> None:
    row = {
        "id": UUID("00000000-0000-0000-0000-000000000003"),
        "key": "ui.example",
        "language": "en",
        "text": "Example",
        "description": "Shown in tests",
    }

    assert api.message_snapshot(row) == {
        "key": "ui.example",
        "language": "en",
        "text": "Example",
        "description": "Shown in tests",
    }
    assert "ui.example" in api.duplicate_message_conflict(row).detail

    events = []
    monkeypatch.setattr(api, "log_event", lambda *args, **fields: events.append((args, fields)))

    api.log_message_event("message_created", "Message created", "admin", row)

    assert events == [
        (
            ("info", "message_created", "success", "Message created"),
            {
                "actor": "admin",
                "resource_type": "message",
                "resource_id": "00000000-0000-0000-0000-000000000003",
                "message_key": "ui.example",
                "language": "en",
            },
        )
    ]


def test_health_and_readiness_routes_use_database_probe(monkeypatch) -> None:
    app = FastAPI()
    api.register_routes(app)
    client = TestClient(app)

    monkeypatch.setattr(api, "_was_ready", False)
    monkeypatch.setattr(api, "query", lambda _sql: [{"ok": 1}])
    assert client.get("/healthz").status_code == 200
    assert client.get("/readyz").status_code == 200

    monkeypatch.setattr(api, "query", lambda _sql: (_ for _ in ()).throw(RuntimeError("down")))
    events = []
    monkeypatch.setattr(api, "log_event", lambda *args, **fields: events.append((args, fields)))

    assert client.get("/readyz").status_code == 503
    assert events[0][0][:3] == ("warn", "dependency_call_failed", "failure")
    assert events[0][1]["dependency"] == "postgres"
