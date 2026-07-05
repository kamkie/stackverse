from __future__ import annotations

from datetime import UTC, datetime


def now_utc() -> datetime:
    current = datetime.now(UTC)
    return current.replace(microsecond=(current.microsecond // 1000) * 1000)


def iso_datetime(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
