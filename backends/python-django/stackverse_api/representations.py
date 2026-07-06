from __future__ import annotations

import math
from collections.abc import Callable
from typing import Any

from .models import AuditEntry, Bookmark, Message, Report, UserAccount
from .problems import omit_none
from .time import iso_datetime

Mapper = Callable[[Any], Any]


def to_bookmark_response(bookmark: Bookmark) -> dict[str, Any]:
    return omit_none(
        {
            "id": str(bookmark.id),
            "url": bookmark.url,
            "title": bookmark.title,
            "notes": bookmark.notes,
            "tags": bookmark.tags or [],
            "visibility": bookmark.visibility,
            "status": bookmark.status,
            "owner": bookmark.owner,
            "createdAt": iso_datetime(bookmark.created_at),
            "updatedAt": iso_datetime(bookmark.updated_at),
        }
    )


def to_report_response(report: Report) -> dict[str, Any]:
    return omit_none(
        {
            "id": str(report.id),
            "bookmarkId": str(report.bookmark_id),
            "reporter": report.reporter,
            "reason": report.reason,
            "comment": report.comment,
            "status": report.status,
            "createdAt": iso_datetime(report.created_at),
            "resolvedBy": report.resolved_by,
            "resolvedAt": iso_datetime(report.resolved_at) if report.resolved_at is not None else None,
            "resolutionNote": report.resolution_note,
        }
    )


def to_message_response(message: Message) -> dict[str, Any]:
    return omit_none(
        {
            "id": str(message.id),
            "key": message.key,
            "language": message.language,
            "text": message.text,
            "description": message.description,
            "createdAt": iso_datetime(message.created_at),
            "updatedAt": iso_datetime(message.updated_at),
        }
    )


def to_user_account_response(account: UserAccount, bookmark_count: int = 0) -> dict[str, Any]:
    return omit_none(
        {
            "username": account.username,
            "firstSeen": iso_datetime(account.first_seen),
            "lastSeen": iso_datetime(account.last_seen),
            "status": account.status,
            "blockedReason": account.blocked_reason,
            "bookmarkCount": int(bookmark_count),
        }
    )


def to_audit_response(entry: AuditEntry) -> dict[str, Any]:
    return omit_none(
        {
            "id": str(entry.id),
            "actor": entry.actor,
            "action": entry.action,
            "targetType": entry.target_type,
            "targetId": entry.target_id,
            "detail": entry.detail,
            "createdAt": iso_datetime(entry.created_at),
        }
    )


def page_of(items: list[Any], page: int, size: int, total: int, mapper: Mapper) -> dict[str, Any]:
    return {
        "items": [mapper(item) for item in items],
        "page": page,
        "size": size,
        "totalItems": total,
        "totalPages": math.ceil(total / size),
    }
