from contextlib import contextmanager
from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID

import pytest

from stackverse_backend import seed


class FakeResult:
    def __init__(self, rowcount: int) -> None:
        self.rowcount = rowcount


class FakeConnection:
    def __init__(self, rowcounts: list[int]) -> None:
        self.rowcounts = rowcounts
        self.calls = []

    def execute(self, sql, params):
        self.calls.append((sql, params))
        return FakeResult(self.rowcounts.pop(0))


def test_seed_messages_inserts_missing_keys_and_logs_inserted_and_skipped_counts(tmp_path, monkeypatch) -> None:
    (tmp_path / "pl.json").write_text('{"ui.greeting":"Czesc","ui.farewell":"Do widzenia"}', encoding="utf-8")
    conn = FakeConnection([1, 0])
    events = []
    now = datetime(2026, 7, 1, 12, 30, tzinfo=UTC)

    @contextmanager
    def fake_transaction():
        yield conn

    monkeypatch.setattr(seed, "config", SimpleNamespace(seed_messages_dir=tmp_path))
    monkeypatch.setattr(seed, "transaction", fake_transaction)
    monkeypatch.setattr(seed, "now_utc", lambda: now)
    monkeypatch.setattr(seed, "uuid4", lambda: UUID("00000000-0000-0000-0000-000000000001"))
    monkeypatch.setattr(seed, "log_event", lambda *args, **fields: events.append((args, fields)))

    seed.seed_messages()

    assert len(conn.calls) == 2
    assert conn.calls[0][1] == (
        "00000000-0000-0000-0000-000000000001",
        "ui.greeting",
        "pl",
        "Czesc",
        now,
        now,
    )
    assert events == [
        (
            ("info", "message_seed_imported", "success", "Message seed 'pl': 1 inserted, 1 already present"),
            {"language": "pl", "inserted": 1, "skipped": 1},
        )
    ]


def test_seed_messages_reports_missing_seed_directory(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(seed, "config", SimpleNamespace(seed_messages_dir=tmp_path / "missing"))

    with pytest.raises(RuntimeError, match="Message seed directory not found"):
        seed.seed_messages()
