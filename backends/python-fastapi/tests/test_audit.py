import json
from datetime import UTC, datetime
from uuid import UUID

from stackverse_backend import audit


class FakeConnection:
    def __init__(self) -> None:
        self.calls = []

    def execute(self, sql, params):
        self.calls.append((sql, params))


def test_record_audit_inserts_immutable_audit_entry_with_json_detail(monkeypatch) -> None:
    conn = FakeConnection()
    created_at = datetime(2026, 7, 1, 12, 30, tzinfo=UTC)
    monkeypatch.setattr(audit, "now_utc", lambda: created_at)
    monkeypatch.setattr(audit, "uuid4", lambda: UUID("00000000-0000-0000-0000-000000000099"))

    audit.record_audit(
        conn,
        "moderator",
        "bookmark.status-changed",
        "bookmark",
        "bookmark-id",
        {"from": "active", "to": "hidden"},
    )

    sql, params = conn.calls[0]
    assert "insert into audit_entries" in sql
    assert params[:5] == (
        "00000000-0000-0000-0000-000000000099",
        "moderator",
        "bookmark.status-changed",
        "bookmark",
        "bookmark-id",
    )
    assert json.loads(params[5]) == {"from": "active", "to": "hidden"}
    assert params[6] == created_at


def test_record_audit_preserves_absent_detail_as_null(monkeypatch) -> None:
    conn = FakeConnection()
    monkeypatch.setattr(audit, "now_utc", lambda: datetime(2026, 7, 1, tzinfo=UTC))
    monkeypatch.setattr(audit, "uuid4", lambda: UUID("00000000-0000-0000-0000-000000000100"))

    audit.record_audit(conn, "admin", "user.unblocked", "user", "demo")

    assert conn.calls[0][1][5] is None
