from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

_UNSET = object()


@dataclass(frozen=True)
class Step:
    contains: str
    one: object = _UNSET
    all: object = _UNSET
    params: tuple[object, ...] | None = None
    raises: Exception | None = None


class FakeResult:
    def __init__(self, step: Step) -> None:
        self._step = step

    def fetchone(self) -> object:
        if self._step.one is not _UNSET:
            return self._step.one
        if self._step.all is not _UNSET:
            rows = list(self._step.all)
            return rows[0] if rows else None
        return None

    def fetchall(self) -> list[object]:
        if self._step.all is not _UNSET:
            return list(self._step.all)
        return []


class ScriptedConnection:
    def __init__(self, *steps: Step) -> None:
        self._steps = list(steps)
        self.calls: list[tuple[str, tuple[object, ...]]] = []

    def execute(self, statement: object, params: tuple[object, ...] = ()) -> FakeResult:
        if not self._steps:
            raise AssertionError(f"Unexpected SQL call: {statement}")
        step = self._steps.pop(0)
        rendered = statement.as_string(None) if hasattr(statement, "as_string") else str(statement)
        normalized = " ".join(rendered.lower().split())
        assert " ".join(step.contains.lower().split()) in normalized
        actual_params = tuple(params)
        if step.params is not None:
            assert actual_params == step.params
        self.calls.append((normalized, actual_params))
        if step.raises is not None:
            raise step.raises
        return FakeResult(step)

    def assert_exhausted(self) -> None:
        assert self._steps == []


@contextmanager
def scripted_transaction(connection: ScriptedConnection) -> Iterator[ScriptedConnection]:
    yield connection


def at_noon() -> datetime:
    return datetime(2026, 7, 1, 12, 0, tzinfo=UTC)


def bookmark_row(**overrides: Any) -> dict[str, Any]:
    row = {
        "id": "00000000-0000-0000-0000-000000000001",
        "owner": "demo",
        "url": "https://example.com",
        "title": "Example",
        "notes": None,
        "tags": ["python"],
        "visibility": "public",
        "status": "active",
        "created_at": at_noon(),
        "updated_at": at_noon(),
    }
    row.update(overrides)
    return row


def report_row(**overrides: Any) -> dict[str, Any]:
    row = {
        "id": "00000000-0000-0000-0000-000000000010",
        "bookmark_id": "00000000-0000-0000-0000-000000000001",
        "reporter": "reporter",
        "reason": "spam",
        "comment": None,
        "status": "open",
        "created_at": at_noon(),
        "resolved_by": None,
        "resolved_at": None,
        "resolution_note": None,
    }
    row.update(overrides)
    return row


def message_row(**overrides: Any) -> dict[str, Any]:
    row = {
        "id": "00000000-0000-0000-0000-000000000020",
        "key": "ui.example",
        "language": "en",
        "text": "Example",
        "description": None,
        "created_at": at_noon(),
        "updated_at": at_noon(),
    }
    row.update(overrides)
    return row


def account_row(**overrides: Any) -> dict[str, Any]:
    row = {
        "username": "demo",
        "first_seen": at_noon(),
        "last_seen": at_noon(),
        "status": "active",
        "blocked_reason": None,
        "bookmark_count": 1,
    }
    row.update(overrides)
    return row


def audit_row(**overrides: Any) -> dict[str, Any]:
    row = {
        "id": "00000000-0000-0000-0000-000000000030",
        "actor": "admin",
        "action": "user.blocked",
        "target_type": "user",
        "target_id": "demo",
        "detail": {"reason": "abuse"},
        "created_at": at_noon(),
    }
    row.update(overrides)
    return row
