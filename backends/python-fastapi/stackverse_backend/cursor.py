from __future__ import annotations

import base64
from dataclasses import dataclass
from datetime import datetime

from .problems import BadRequestProblem, UUID_PATTERN
from .time import iso_datetime


@dataclass(frozen=True)
class BookmarkCursor:
    created_at: datetime
    id: str


def encode_cursor(cursor: BookmarkCursor) -> str:
    raw = f"{iso_datetime(cursor.created_at)}|{cursor.id}".encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def decode_cursor(value: str) -> BookmarkCursor:
    try:
        padded = value + "=" * (-len(value) % 4)
        decoded = base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8")
        created, bookmark_id = decoded.split("|", 1)
        parsed = datetime.fromisoformat(created.replace("Z", "+00:00"))
    except Exception as exc:
        raise BadRequestProblem("The cursor is malformed or unresolvable.") from exc
    if not UUID_PATTERN.fullmatch(bookmark_id):
        raise BadRequestProblem("The cursor is malformed or unresolvable.")
    return BookmarkCursor(parsed, bookmark_id)
