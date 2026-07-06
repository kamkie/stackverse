from __future__ import annotations

from datetime import UTC, datetime

from django.utils import timezone


def now_utc() -> datetime:
    return timezone.now()


def iso_datetime(value: datetime) -> str:
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
