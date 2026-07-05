from __future__ import annotations

import math
import re
from collections.abc import Callable
from datetime import datetime
from threading import Lock
from typing import Any
from urllib.parse import urlparse

from fastapi import Request
from psycopg import Connection, sql

from .audit import record_audit
from .auth import Caller
from .db import one, query
from .logging_setup import log_event
from .problems import (
    BadRequestProblem,
    ConflictProblem,
    NotFoundProblem,
    UnauthorizedProblem,
    Validator,
    escape_like,
    multi_param,
    omit_none,
    require_max_length,
    single_param,
)
from .time import iso_datetime, now_utc

V1_BOOKMARKS_DEPRECATION = "@1782864000"
V1_BOOKMARKS_SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT"
V1_BOOKMARKS_SUCCESSOR = '</api/v2/bookmarks>; rel="successor-version"'

TAG_PATTERN = re.compile(r"^[a-z0-9-]{1,30}$")
KEY_PATTERN = re.compile(r"^[a-z0-9-]+(\.[a-z0-9-]+)*$")
LANGUAGE_PATTERN = re.compile(r"^[a-z]{2}$")
VISIBILITIES = {"private", "public"}
REPORT_REASONS = {"spam", "offensive", "broken-link", "other"}
REPORT_STATUSES = {"open", "dismissed", "actioned"}
_readiness_lock = Lock()
_was_ready = True

Row = dict[str, Any]
Mapper = Callable[[Row], Any]


def to_bookmark_response(row: Row) -> dict[str, Any]:
    return omit_none(
        {
            "id": str(row["id"]),
            "url": row["url"],
            "title": row["title"],
            "notes": row["notes"],
            "tags": row["tags"] or [],
            "visibility": row["visibility"],
            "status": row["status"],
            "owner": row["owner"],
            "createdAt": iso_datetime(row["created_at"]),
            "updatedAt": iso_datetime(row["updated_at"]),
        }
    )


def to_report_response(row: Row) -> dict[str, Any]:
    return omit_none(
        {
            "id": str(row["id"]),
            "bookmarkId": str(row["bookmark_id"]),
            "reporter": row["reporter"],
            "reason": row["reason"],
            "comment": row["comment"],
            "status": row["status"],
            "createdAt": iso_datetime(row["created_at"]),
            "resolvedBy": row["resolved_by"],
            "resolvedAt": iso_datetime(row["resolved_at"]) if row["resolved_at"] is not None else None,
            "resolutionNote": row["resolution_note"],
        }
    )


def to_message_response(row: Row) -> dict[str, Any]:
    return omit_none(
        {
            "id": str(row["id"]),
            "key": row["key"],
            "language": row["language"],
            "text": row["text"],
            "description": row["description"],
            "createdAt": iso_datetime(row["created_at"]),
            "updatedAt": iso_datetime(row["updated_at"]),
        }
    )


def to_user_account_response(row: Row) -> dict[str, Any]:
    return omit_none(
        {
            "username": row["username"],
            "firstSeen": iso_datetime(row["first_seen"]),
            "lastSeen": iso_datetime(row["last_seen"]),
            "status": row["status"],
            "blockedReason": row["blocked_reason"],
            "bookmarkCount": int(row["bookmark_count"]),
        }
    )


def to_audit_response(row: Row) -> dict[str, Any]:
    return omit_none(
        {
            "id": str(row["id"]),
            "actor": row["actor"],
            "action": row["action"],
            "targetType": row["target_type"],
            "targetId": row["target_id"],
            "detail": row["detail"],
            "createdAt": iso_datetime(row["created_at"]),
        }
    )


def page_of(rows: list[Row], page: int, size: int, total: int, mapper: Mapper) -> dict[str, Any]:
    return {
        "items": [mapper(row) for row in rows],
        "page": page,
        "size": size,
        "totalItems": total,
        "totalPages": math.ceil(total / size),
    }


def validate_bookmark_input(body: Any) -> dict[str, Any]:
    input_data = body if isinstance(body, dict) else {}
    validator = Validator()
    url = input_data.get("url").strip() if isinstance(input_data.get("url"), str) else ""
    if not url:
        validator.reject("url", "validation.url.required")
    else:
        validator.check(len(url) <= 2000 and is_http_url(url), "url", "validation.url.invalid")

    title = input_data.get("title").strip() if isinstance(input_data.get("title"), str) else ""
    validator.check(title != "", "title", "validation.title.required")
    validator.check(len(title) <= 200, "title", "validation.title.too-long")

    notes = input_data.get("notes") if isinstance(input_data.get("notes"), str) else None
    validator.check(len(notes or "") <= 4000, "notes", "validation.notes.too-long")

    raw_tags = input_data.get("tags") if isinstance(input_data.get("tags"), list) else []
    tags = list(dict.fromkeys(str(tag).strip().lower() for tag in raw_tags))
    validator.check(len(tags) <= 10, "tags", "validation.tags.too-many")
    validator.check(all(TAG_PATTERN.fullmatch(tag) for tag in tags), "tags", "validation.tag.invalid")

    visibility = input_data.get("visibility", "private")
    if visibility not in VISIBILITIES:
        raise BadRequestProblem(f"unknown visibility: {visibility}")
    validator.throw_if_invalid()
    return {"url": url, "title": title, "notes": notes, "tags": tags, "visibility": visibility}


def is_http_url(value: str) -> bool:
    parsed = urlparse(value)
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


def validate_query_tags(raw_tags: list[str]) -> list[str]:
    tags = [tag.strip().lower() for tag in raw_tags]
    validator = Validator()
    validator.check(all(TAG_PATTERN.fullmatch(tag) for tag in tags), "tag", "validation.tag.invalid")
    validator.throw_if_invalid()
    return tags


def parse_bookmark_filters(request: Request) -> dict[str, Any]:
    q = single_param(request, "q")
    require_max_length(q, 200, "q")
    visibility = single_param(request, "visibility")
    if visibility is not None and visibility not in VISIBILITIES:
        raise BadRequestProblem(f"unknown visibility: {visibility}")
    return {"tags": validate_query_tags(multi_param(request, "tag")), "q": q, "visibility": visibility}


def listing_where(caller: Caller | None, filters: dict[str, Any]) -> tuple[sql.Composable, tuple[Any, ...]]:
    conditions: list[sql.Composable] = []
    params: tuple[Any, ...] = ()
    if filters["visibility"] == "public":
        conditions.append(sql.SQL("visibility = 'public' and status = 'active'"))
    else:
        if caller is None:
            raise UnauthorizedProblem()
        conditions.append(sql.SQL("owner = %s"))
        params = (*params, caller.username)
        if filters["visibility"] is not None:
            conditions.append(sql.SQL("visibility = %s"))
            params = (*params, filters["visibility"])
    if filters["tags"]:
        conditions.append(sql.SQL("tags @> %s::text[]"))
        params = (*params, filters["tags"])
    if filters["q"] is not None and filters["q"].strip():
        conditions.append(sql.SQL("(title ilike %s escape E'\\\\' or notes ilike %s escape E'\\\\')"))
        pattern = f"%{escape_like(filters['q'])}%"
        params = (*params, pattern, pattern)
    return where_clause(conditions), params


def where_clause(conditions: list[sql.Composable]) -> sql.Composable:
    return sql.SQL(" and ").join(conditions) if conditions else sql.SQL("true")


def find_bookmark(bookmark_id: str) -> dict[str, Any] | None:
    return one("select * from bookmarks where id = %s", (bookmark_id,))


def owned_by_caller(username: str, bookmark_id: str) -> dict[str, Any]:
    bookmark = find_bookmark(bookmark_id)
    if bookmark is None or bookmark["owner"] != username:
        raise NotFoundProblem()
    return bookmark


def visible_to(bookmark: dict[str, Any], username: str | None) -> bool:
    return bookmark["owner"] == username or (bookmark["visibility"] == "public" and bookmark["status"] == "active")


def validate_report_input(body: Any) -> dict[str, Any]:
    input_data = body if isinstance(body, dict) else {}
    validator = Validator()
    reason = input_data.get("reason")
    validator.check(isinstance(reason, str) and reason in REPORT_REASONS, "reason", "validation.report.reason.invalid")
    comment = input_data.get("comment") if isinstance(input_data.get("comment"), str) else None
    validator.check(len(comment or "") <= 1000, "comment", "validation.report.comment.too-long")
    validator.throw_if_invalid()
    return {"reason": reason, "comment": comment}


def validated_report_status(value: str | None) -> str | None:
    if value is None:
        return None
    if value not in REPORT_STATUSES:
        raise BadRequestProblem(f"unknown status: {value}")
    return value


def own_report(conn: Connection, reporter: str, report_id: str) -> dict[str, Any]:
    report = conn.execute("select * from reports where id = %s for update", (report_id,)).fetchone()
    if report is None or report["reporter"] != reporter:
        raise NotFoundProblem()
    return report


def require_open(report: dict[str, Any]) -> None:
    if report["status"] != "open":
        raise ConflictProblem("The report has already been resolved.")


def validate_resolution_input(body: Any) -> tuple[str, str | None]:
    input_data = body if isinstance(body, dict) else {}
    validator = Validator()
    resolution = input_data.get("resolution")
    validator.check(
        isinstance(resolution, str) and resolution in REPORT_STATUSES, "resolution", "validation.resolution.invalid"
    )
    note = input_data.get("note") if isinstance(input_data.get("note"), str) else None
    validator.check(len(note or "") <= 1000, "note", "validation.resolution.note.too-long")
    validator.throw_if_invalid()
    return str(resolution), note


def resolve_one(
    conn: Connection,
    report: dict[str, Any],
    resolution: str,
    actor: str,
    note: str | None,
    auto_resolved: bool,
) -> dict[str, Any]:
    row = conn.execute(
        """
        update reports
        set status = %s, resolved_by = %s, resolved_at = %s, resolution_note = %s
        where id = %s
        returning *
        """,
        (resolution, actor, now_utc(), note, report["id"]),
    ).fetchone()
    record_audit(
        conn,
        actor,
        "report.resolved",
        "report",
        str(report["id"]),
        {
            "bookmarkId": str(report["bookmark_id"]),
            "resolution": resolution,
            "note": note,
            "autoResolved": auto_resolved,
        },
    )
    log_event(
        "info",
        "report_resolved",
        "success",
        "Report resolved",
        actor=actor,
        resource_type="report",
        resource_id=str(report["id"]),
        bookmark_id=str(report["bookmark_id"]),
        resolution=resolution,
        auto_resolved=auto_resolved,
    )
    return row


def hide_bookmark(conn: Connection, actor: str, bookmark_id: str, note: str | None) -> None:
    bookmark = conn.execute("select * from bookmarks where id = %s", (bookmark_id,)).fetchone()
    if bookmark is None:
        raise NotFoundProblem()
    if bookmark["status"] == "hidden":
        return
    conn.execute("update bookmarks set status = 'hidden', updated_at = %s where id = %s", (now_utc(), bookmark_id))
    record_audit(
        conn,
        actor,
        "bookmark.status-changed",
        "bookmark",
        bookmark_id,
        {"from": "active", "to": "hidden", "note": note},
    )
    log_event(
        "info",
        "bookmark_status_changed",
        "success",
        "Bookmark hidden by an actioned report",
        actor=actor,
        resource_type="bookmark",
        resource_id=bookmark_id,
        **{"from": "active", "to": "hidden"},
    )


def validate_bookmark_status_input(body: Any) -> tuple[str, str | None]:
    input_data = body if isinstance(body, dict) else {}
    validator = Validator()
    status = input_data.get("status")
    validator.check(status in {"active", "hidden"}, "status", "validation.bookmark-status.invalid")
    note = input_data.get("note") if isinstance(input_data.get("note"), str) else None
    validator.check(len(note or "") <= 1000, "note", "validation.bookmark-status.note.too-long")
    validator.throw_if_invalid()
    return str(status), note


WITH_BOOKMARK_COUNT = sql.SQL(
    """
    select u.*, (select count(*)::int from bookmarks b where b.owner = u.username) as bookmark_count
    from user_accounts u
    """
)


def find_account(username: str) -> dict[str, Any] | None:
    return one(sql.SQL("{} where u.username = %s").format(WITH_BOOKMARK_COUNT), (username,))


def date_param(value: str | None, name: str) -> datetime | None:
    if value is None:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise BadRequestProblem(f"{name} must be an RFC 3339 date-time") from exc


def swap_readiness(ready: bool) -> bool:
    global _was_ready
    with _readiness_lock:
        previous = _was_ready
        if previous == ready:
            return False
        _was_ready = ready
        return True


COUNT_PER_DAY_IDENTIFIERS = {
    ("bookmarks", "created_at"),
    ("user_accounts", "last_seen"),
}


def count_per_day(table: str, column: str, start: datetime) -> dict[str, int]:
    if (table, column) not in COUNT_PER_DAY_IDENTIFIERS:
        raise ValueError("unsupported daily series")
    rows = query(
        sql.SQL(
            """
            select ({column} at time zone 'UTC')::date::text as day, count(*)::int as count
            from {table}
            where {column} >= %s
            group by day
            """
        ).format(table=sql.Identifier(table), column=sql.Identifier(column)),
        (start,),
    )
    return {row["day"]: int(row["count"]) for row in rows}


def validate_message_input(body: Any) -> dict[str, Any]:
    input_data = body if isinstance(body, dict) else {}
    validator = Validator()
    key = input_data.get("key").strip() if isinstance(input_data.get("key"), str) else ""
    validator.check(bool(KEY_PATTERN.fullmatch(key)) and len(key) <= 150, "key", "validation.message.key.invalid")
    language = input_data.get("language").strip() if isinstance(input_data.get("language"), str) else ""
    validator.check(bool(LANGUAGE_PATTERN.fullmatch(language)), "language", "validation.message.language.invalid")
    text = input_data.get("text") if isinstance(input_data.get("text"), str) else ""
    validator.check(text != "", "text", "validation.message.text.required")
    validator.check(len(text) <= 2000, "text", "validation.message.text.too-long")
    description = input_data.get("description") if isinstance(input_data.get("description"), str) else None
    validator.check(len(description or "") <= 1000, "description", "validation.message.description.too-long")
    validator.throw_if_invalid()
    return {"key": key, "language": language, "text": text, "description": description}


def duplicate_message_conflict(input_data: dict[str, Any]) -> ConflictProblem:
    return ConflictProblem(
        f"A message with key '{input_data['key']}' and language '{input_data['language']}' already exists."
    )


def message_snapshot(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "key": row["key"],
        "language": row["language"],
        "text": row["text"],
        "description": row["description"],
    }


def log_message_event(event: str, description: str, actor: str, message: dict[str, Any]) -> None:
    log_event(
        "info",
        event,
        "success",
        description,
        actor=actor,
        resource_type="message",
        resource_id=str(message["id"]),
        message_key=message["key"],
        language=message["language"],
    )
