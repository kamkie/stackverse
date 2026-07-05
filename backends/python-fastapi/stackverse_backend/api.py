from __future__ import annotations

import math
import re
from datetime import UTC, datetime, timedelta
from typing import Any
from urllib.parse import urlparse
from uuid import uuid4

from fastapi import Body, FastAPI, Request, Response
from psycopg import Connection
from psycopg.errors import UniqueViolation

from .audit import record_audit
from .auth import me_response, require_caller, require_role
from .cursor import BookmarkCursor, decode_cursor, encode_cursor
from .db import execute, one, query, transaction
from .etag import response_with_etag
from .i18n import message_bundle, resolve_language
from .logging_setup import log_event
from .problems import (
    BadRequestProblem,
    ConflictProblem,
    NotFoundProblem,
    Validator,
    escape_like,
    first_param,
    multi_param,
    omit_none,
    parse_uuid,
    require_max_length,
    require_valid_paging,
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


def register_routes(app: FastAPI) -> None:
    @app.get("/healthz")
    def healthz() -> Response:
        return Response(status_code=200)

    @app.get("/readyz")
    def readyz() -> Response:
        try:
            query("select 1")
            return Response(status_code=200)
        except Exception as exc:
            log_event(
                "error",
                "dependency_call_failed",
                "failure",
                "PostgreSQL readiness check failed",
                dependency="postgres",
                error_code=exc.__class__.__name__.lower(),
            )
            return Response(status_code=503)

    @app.get("/api/v1/me")
    def me(request: Request) -> dict[str, Any]:
        return me_response(require_caller(request))

    @app.get("/api/v1/bookmarks")
    def list_bookmarks_v1(request: Request, response: Response) -> dict[str, Any]:
        response.headers["Deprecation"] = V1_BOOKMARKS_DEPRECATION
        response.headers["Sunset"] = V1_BOOKMARKS_SUNSET
        response.headers["Link"] = V1_BOOKMARKS_SUCCESSOR
        page, size = require_valid_paging(request)
        filters = parse_bookmark_filters(request)
        where, params = listing_where(getattr(request.state, "caller", None), filters)
        items = query(
            f"""
            select * from bookmarks where {where}
            order by created_at desc, id desc
            limit %s offset %s
            """,
            (*params, size, page * size),
        )
        total = int(one(f"select count(*)::int as count from bookmarks where {where}", params)["count"])
        return page_of(items, page, size, total, to_bookmark_response)

    @app.get("/api/v2/bookmarks")
    def list_bookmarks_v2(request: Request) -> dict[str, Any]:
        _page, size = require_valid_paging(request)
        filters = parse_bookmark_filters(request)
        raw_cursor = single_param(request, "cursor")
        cursor = decode_cursor(raw_cursor) if raw_cursor is not None else None
        where, params = listing_where(getattr(request.state, "caller", None), filters)
        cursor_sql = ""
        if cursor is not None:
            cursor_sql = " and (created_at < %s or (created_at = %s and id < %s::uuid))"
            params = (*params, cursor.created_at, cursor.created_at, cursor.id)
        rows = query(
            f"""
            select * from bookmarks where {where}{cursor_sql}
            order by created_at desc, id desc
            limit %s
            """,
            (*params, size + 1),
        )
        items = rows[:size]
        payload: dict[str, Any] = {"items": [to_bookmark_response(row) for row in items]}
        if len(rows) > size and items:
            last = items[-1]
            payload["nextCursor"] = encode_cursor(BookmarkCursor(last["created_at"], str(last["id"])))
        return payload

    @app.post("/api/v1/bookmarks", status_code=201)
    def create_bookmark(request: Request, response: Response, body: Any = Body(None)) -> dict[str, Any]:
        caller = require_caller(request)
        input_data = validate_bookmark_input(body)
        bookmark_id = str(uuid4())
        now = now_utc()
        row = one(
            """
            insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)
            values (%s, %s, %s, %s, %s, %s::text[], %s, 'active', %s, %s)
            returning *
            """,
            (
                bookmark_id,
                caller.username,
                input_data["url"],
                input_data["title"],
                input_data["notes"],
                input_data["tags"],
                input_data["visibility"],
                now,
                now,
            ),
        )
        response.headers["Location"] = f"/api/v1/bookmarks/{bookmark_id}"
        return to_bookmark_response(row)

    @app.get("/api/v1/bookmarks/{bookmark_id}")
    def get_bookmark(request: Request, bookmark_id: str) -> dict[str, Any]:
        bookmark = find_bookmark(parse_uuid(bookmark_id))
        caller = getattr(request.state, "caller", None)
        username = caller.username if caller else None
        if bookmark is None or not visible_to(bookmark, username):
            raise NotFoundProblem()
        return to_bookmark_response(bookmark)

    @app.put("/api/v1/bookmarks/{bookmark_id}")
    def update_bookmark(request: Request, bookmark_id: str, body: Any = Body(None)) -> dict[str, Any]:
        caller = require_caller(request)
        bookmark_id = parse_uuid(bookmark_id)
        input_data = validate_bookmark_input(body)
        with transaction() as conn:
            bookmark = conn.execute("select * from bookmarks where id = %s for update", (bookmark_id,)).fetchone()
            if bookmark is None or bookmark["owner"] != caller.username:
                raise NotFoundProblem()
            if bookmark["status"] == "hidden" and input_data["visibility"] == "public":
                raise ConflictProblem(
                    "This bookmark was hidden by moderation and cannot be made public.",
                    "error.bookmark.hidden-publish",
                )
            updated = conn.execute(
                """
                update bookmarks
                set url = %s, title = %s, notes = %s, tags = %s::text[], visibility = %s, updated_at = %s
                where id = %s
                returning *
                """,
                (
                    input_data["url"],
                    input_data["title"],
                    input_data["notes"],
                    input_data["tags"],
                    input_data["visibility"],
                    now_utc(),
                    bookmark_id,
                ),
            ).fetchone()
        return to_bookmark_response(updated)

    @app.delete("/api/v1/bookmarks/{bookmark_id}", status_code=204)
    def delete_bookmark(request: Request, bookmark_id: str) -> Response:
        caller = require_caller(request)
        bookmark = owned_by_caller(caller.username, parse_uuid(bookmark_id))
        execute("delete from bookmarks where id = %s", (bookmark["id"],))
        return Response(status_code=204)

    @app.get("/api/v1/tags")
    def list_tags(request: Request) -> dict[str, Any]:
        caller = require_caller(request)
        rows = query(
            """
            select tag, count(*)::int as count
            from bookmarks, unnest(tags) as tag
            where owner = %s
            group by tag
            order by count desc, tag asc
            """,
            (caller.username,),
        )
        return {"tags": rows}

    @app.post("/api/v1/bookmarks/{bookmark_id}/reports", status_code=201)
    def report_bookmark(request: Request, bookmark_id: str, body: Any = Body(None)) -> dict[str, Any]:
        caller = require_caller(request)
        bookmark_id = parse_uuid(bookmark_id)
        input_data = validate_report_input(body)
        with transaction() as conn:
            bookmark = conn.execute(
                "select visibility, status from bookmarks where id = %s for update",
                (bookmark_id,),
            ).fetchone()
            if bookmark is None or bookmark["visibility"] != "public" or bookmark["status"] != "active":
                raise NotFoundProblem()
            open_report = conn.execute(
                "select 1 from reports where bookmark_id = %s and reporter = %s and status = 'open'",
                (bookmark_id, caller.username),
            ).fetchone()
            if open_report:
                raise ConflictProblem("You already have an open report on this bookmark.")
            try:
                row = conn.execute(
                    """
                    insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
                    values (%s, %s, %s, %s, %s, 'open', %s)
                    returning *
                    """,
                    (str(uuid4()), bookmark_id, caller.username, input_data["reason"], input_data["comment"], now_utc()),
                ).fetchone()
            except UniqueViolation as exc:
                raise ConflictProblem("You already have an open report on this bookmark.") from exc
        log_event(
            "info",
            "report_created",
            "success",
            "Report created on a public bookmark",
            actor=caller.username,
            resource_type="report",
            resource_id=str(row["id"]),
            bookmark_id=bookmark_id,
            reason=row["reason"],
        )
        return to_report_response(row)

    @app.get("/api/v1/reports")
    def list_my_reports(request: Request) -> dict[str, Any]:
        caller = require_caller(request)
        page, size = require_valid_paging(request)
        status = validated_report_status(single_param(request, "status"))
        conditions = ["reporter = %s"]
        params: tuple[Any, ...] = (caller.username,)
        if status is not None:
            conditions.append("status = %s")
            params = (*params, status)
        where = " and ".join(conditions)
        rows = query(
            f"""
            select * from reports where {where}
            order by created_at desc, id desc
            limit %s offset %s
            """,
            (*params, size, page * size),
        )
        total = int(one(f"select count(*)::int as count from reports where {where}", params)["count"])
        return page_of(rows, page, size, total, to_report_response)

    @app.put("/api/v1/reports/{report_id}")
    def update_my_report(request: Request, report_id: str, body: Any = Body(None)) -> dict[str, Any]:
        caller = require_caller(request)
        report_id = parse_uuid(report_id)
        input_data = validate_report_input(body)
        with transaction() as conn:
            report = own_report(conn, caller.username, report_id)
            require_open(report)
            updated = conn.execute(
                "update reports set reason = %s, comment = %s where id = %s returning *",
                (input_data["reason"], input_data["comment"], report_id),
            ).fetchone()
        log_event(
            "info",
            "report_updated",
            "success",
            "Report updated by its reporter",
            actor=caller.username,
            resource_type="report",
            resource_id=report_id,
            bookmark_id=str(report["bookmark_id"]),
            reason=input_data["reason"],
        )
        return to_report_response(updated)

    @app.delete("/api/v1/reports/{report_id}", status_code=204)
    def withdraw_report(request: Request, report_id: str) -> Response:
        caller = require_caller(request)
        report_id = parse_uuid(report_id)
        with transaction() as conn:
            report = own_report(conn, caller.username, report_id)
            require_open(report)
            conn.execute("delete from reports where id = %s", (report_id,))
        log_event(
            "info",
            "report_withdrawn",
            "success",
            "Report withdrawn by its reporter",
            actor=caller.username,
            resource_type="report",
            resource_id=report_id,
            bookmark_id=str(report["bookmark_id"]),
        )
        return Response(status_code=204)

    @app.get("/api/v1/admin/reports")
    def list_reports(request: Request) -> dict[str, Any]:
        require_role(request, "moderator")
        page, size = require_valid_paging(request)
        status = validated_report_status(single_param(request, "status")) or "open"
        rows = query(
            """
            select * from reports where status = %s
            order by created_at asc, id asc
            limit %s offset %s
            """,
            (status, size, page * size),
        )
        total = int(one("select count(*)::int as count from reports where status = %s", (status,))["count"])
        return page_of(rows, page, size, total, to_report_response)

    @app.put("/api/v1/admin/reports/{report_id}")
    def resolve_report(request: Request, report_id: str, body: Any = Body(None)) -> dict[str, Any]:
        caller = require_role(request, "moderator")
        report_id = parse_uuid(report_id)
        target, note = validate_resolution_input(body)
        with transaction() as conn:
            if target == "actioned":
                scalar = conn.execute("select bookmark_id from reports where id = %s", (report_id,)).fetchone()
                if scalar is None:
                    raise NotFoundProblem()
                conn.execute("select id from bookmarks where id = %s for update", (scalar["bookmark_id"],))
            report = conn.execute("select * from reports where id = %s for update", (report_id,)).fetchone()
            if report is None:
                raise NotFoundProblem()
            if target == "open":
                conflict = conn.execute(
                    """
                    select 1 from reports
                    where bookmark_id = %s and reporter = %s and status = 'open' and id <> %s
                    """,
                    (report["bookmark_id"], report["reporter"], report_id),
                ).fetchone()
                if conflict:
                    raise ConflictProblem("The reporter already has another open report on this bookmark.")
                try:
                    reopened = conn.execute(
                        """
                        update reports
                        set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null
                        where id = %s
                        returning *
                        """,
                        (report_id,),
                    ).fetchone()
                except UniqueViolation as exc:
                    raise ConflictProblem("The reporter already has another open report on this bookmark.") from exc
                record_audit(conn, caller.username, "report.reopened", "report", report_id, {"bookmarkId": str(report["bookmark_id"])})
                log_event(
                    "info",
                    "report_reopened",
                    "success",
                    "Report re-opened",
                    actor=caller.username,
                    resource_type="report",
                    resource_id=report_id,
                    bookmark_id=str(report["bookmark_id"]),
                )
                return to_report_response(reopened)

            resolved = resolve_one(conn, report, target, caller.username, note, False)
            if target == "actioned":
                hide_bookmark(conn, caller.username, str(report["bookmark_id"]), note)
                siblings = conn.execute(
                    """
                    select * from reports
                    where bookmark_id = %s and status = 'open' and id <> %s
                    order by id asc for update
                    """,
                    (report["bookmark_id"], report_id),
                ).fetchall()
                for sibling in siblings:
                    resolve_one(conn, sibling, "actioned", caller.username, note, True)
        return to_report_response(resolved)

    @app.put("/api/v1/admin/bookmarks/{bookmark_id}/status")
    def set_bookmark_status(request: Request, bookmark_id: str, body: Any = Body(None)) -> dict[str, Any]:
        caller = require_role(request, "moderator")
        bookmark_id = parse_uuid(bookmark_id)
        status, note = validate_bookmark_status_input(body)
        with transaction() as conn:
            bookmark = conn.execute("select * from bookmarks where id = %s for update", (bookmark_id,)).fetchone()
            if bookmark is None:
                raise NotFoundProblem()
            updated = conn.execute(
                "update bookmarks set status = %s, updated_at = %s where id = %s returning *",
                (status, now_utc(), bookmark_id),
            ).fetchone()
            record_audit(
                conn,
                caller.username,
                "bookmark.status-changed",
                "bookmark",
                bookmark_id,
                {"from": bookmark["status"], "to": status, "note": note},
            )
        log_event(
            "info",
            "bookmark_status_changed",
            "success",
            "Bookmark moderation status changed",
            actor=caller.username,
            resource_type="bookmark",
            resource_id=bookmark_id,
            **{"from": bookmark["status"], "to": status},
        )
        return to_bookmark_response(updated)

    @app.get("/api/v1/admin/users")
    def list_users(request: Request) -> dict[str, Any]:
        require_role(request, "admin")
        page, size = require_valid_paging(request)
        q = single_param(request, "q")
        require_max_length(q, 100, "q")
        status = single_param(request, "status")
        if status is not None and status not in {"active", "blocked"}:
            raise BadRequestProblem(f"unknown status: {status}")
        conditions = ["true"]
        params: tuple[Any, ...] = ()
        if q is not None and q.strip():
            conditions.append("u.username ilike %s escape E'\\\\'")
            params = (*params, f"%{escape_like(q)}%")
        if status is not None:
            conditions.append("u.status = %s")
            params = (*params, status)
        where = " and ".join(conditions)
        rows = query(
            f"""
            {WITH_BOOKMARK_COUNT} where {where}
            order by u.last_seen desc, u.username asc
            limit %s offset %s
            """,
            (*params, size, page * size),
        )
        total = int(one(f"select count(*)::int as count from user_accounts u where {where}", params)["count"])
        return page_of(rows, page, size, total, to_user_account_response)

    @app.get("/api/v1/admin/users/{username}")
    def get_user(request: Request, username: str) -> dict[str, Any]:
        require_role(request, "admin")
        account = find_account(username)
        if account is None:
            raise NotFoundProblem()
        return to_user_account_response(account)

    @app.put("/api/v1/admin/users/{username}/status")
    def set_user_status(request: Request, username: str, body: Any = Body(None)) -> dict[str, Any]:
        caller = require_role(request, "admin")
        input_data = body if isinstance(body, dict) else {}
        status = input_data.get("status")
        if status not in {"active", "blocked"}:
            raise BadRequestProblem("status is required")
        reason = input_data.get("reason").strip() if isinstance(input_data.get("reason"), str) else None
        if status == "blocked":
            validator = Validator()
            validator.check(reason is not None and reason != "", "reason", "validation.block.reason.required")
            validator.check(len(reason or "") <= 1000, "reason", "validation.block.reason.too-long")
            validator.throw_if_invalid()
            if username == caller.username:
                raise ConflictProblem("Admins cannot block themselves.")
        with transaction() as conn:
            existing = conn.execute("select username from user_accounts where username = %s for update", (username,)).fetchone()
            if existing is None:
                raise NotFoundProblem()
            if status == "blocked":
                conn.execute(
                    "update user_accounts set status = 'blocked', blocked_reason = %s where username = %s",
                    (reason, username),
                )
                record_audit(conn, caller.username, "user.blocked", "user", username, {"reason": reason})
            else:
                conn.execute(
                    "update user_accounts set status = 'active', blocked_reason = null where username = %s",
                    (username,),
                )
                record_audit(conn, caller.username, "user.unblocked", "user", username)
        log_event(
            "info",
            "user_blocked" if status == "blocked" else "user_unblocked",
            "success",
            "User account blocked" if status == "blocked" else "User account unblocked",
            actor=caller.username,
            resource_type="user",
            resource_id=username,
        )
        account = find_account(username)
        if account is None:
            raise NotFoundProblem()
        return to_user_account_response(account)

    @app.get("/api/v1/admin/audit-log")
    def audit_log(request: Request) -> dict[str, Any]:
        require_role(request, "admin")
        page, size = require_valid_paging(request)
        conditions = ["true"]
        params: tuple[Any, ...] = ()
        for parameter, column in (
            ("actor", "actor"),
            ("action", "action"),
            ("targetType", "target_type"),
            ("targetId", "target_id"),
        ):
            value = single_param(request, parameter)
            if value is not None:
                conditions.append(f"{column} = %s")
                params = (*params, value)
        from_value = date_param(single_param(request, "from"), "from")
        if from_value is not None:
            conditions.append("created_at >= %s")
            params = (*params, from_value)
        to_value = date_param(single_param(request, "to"), "to")
        if to_value is not None:
            conditions.append("created_at <= %s")
            params = (*params, to_value)
        where = " and ".join(conditions)
        rows = query(
            f"""
            select * from audit_entries where {where}
            order by created_at desc, id desc
            limit %s offset %s
            """,
            (*params, size, page * size),
        )
        total = int(one(f"select count(*)::int as count from audit_entries where {where}", params)["count"])
        return page_of(rows, page, size, total, to_audit_response)

    @app.get("/api/v1/admin/stats")
    def stats(request: Request) -> Response:
        require_role(request, "moderator")
        today = datetime.now(UTC).replace(hour=0, minute=0, second=0, microsecond=0)
        start = today - timedelta(days=29)
        created_per_day = count_per_day("bookmarks", "created_at", start)
        active_per_day = count_per_day("user_accounts", "last_seen", start)
        daily = [
            {
                "date": (start + timedelta(days=offset)).date().isoformat(),
                "bookmarksCreated": created_per_day.get((start + timedelta(days=offset)).date().isoformat(), 0),
                "activeUsers": active_per_day.get((start + timedelta(days=offset)).date().isoformat(), 0),
            }
            for offset in range(30)
        ]
        payload = {
            "totals": {
                "users": count("select count(*)::int as count from user_accounts"),
                "bookmarks": count("select count(*)::int as count from bookmarks"),
                "publicBookmarks": count("select count(*)::int as count from bookmarks where visibility = 'public'"),
                "hiddenBookmarks": count("select count(*)::int as count from bookmarks where status = 'hidden'"),
                "openReports": count("select count(*)::int as count from reports where status = 'open'"),
            },
            "daily": daily,
            "topTags": query(
                """
                select tag, count(*)::int as count
                from bookmarks, unnest(tags) as tag
                group by tag
                order by count desc, tag asc
                limit 10
                """
            ),
        }
        return response_with_etag(request, payload)

    @app.get("/api/v1/messages")
    def list_messages(request: Request) -> Response:
        page, size = require_valid_paging(request)
        key = single_param(request, "key")
        language = single_param(request, "language")
        q = single_param(request, "q")
        require_max_length(q, 200, "q")
        conditions = ["true"]
        params: tuple[Any, ...] = ()
        if key is not None:
            conditions.append("key = %s")
            params = (*params, key)
        if language is not None:
            conditions.append("language = %s")
            params = (*params, language)
        if q is not None and q.strip():
            conditions.append("(key ilike %s escape E'\\\\' or text ilike %s escape E'\\\\')")
            pattern = f"%{escape_like(q)}%"
            params = (*params, pattern, pattern)
        where = " and ".join(conditions)
        rows = query(
            f"select * from messages where {where} order by key, language limit %s offset %s",
            (*params, size, page * size),
        )
        total = int(one(f"select count(*)::int as count from messages where {where}", params)["count"])
        return response_with_etag(request, page_of(rows, page, size, total, to_message_response))

    @app.get("/api/v1/messages/bundle")
    def get_bundle(request: Request) -> Response:
        language = resolve_language(first_param(request, "lang"), request.headers.get("accept-language"))
        return response_with_etag(
            request,
            {"language": language, "messages": message_bundle(language)},
            {"Content-Language": language},
        )

    @app.get("/api/v1/messages/{message_id}")
    def get_message(request: Request, message_id: str) -> Response:
        message = one("select * from messages where id = %s", (parse_uuid(message_id),))
        if message is None:
            raise NotFoundProblem()
        return response_with_etag(request, to_message_response(message))

    @app.post("/api/v1/messages", status_code=201)
    def create_message(request: Request, response: Response, body: Any = Body(None)) -> dict[str, Any]:
        caller = require_role(request, "admin")
        input_data = validate_message_input(body)
        message_id = str(uuid4())
        now = now_utc()
        with transaction() as conn:
            duplicate = conn.execute(
                "select 1 from messages where key = %s and language = %s",
                (input_data["key"], input_data["language"]),
            ).fetchone()
            if duplicate:
                raise duplicate_message_conflict(input_data)
            try:
                row = conn.execute(
                    """
                    insert into messages (id, key, language, text, description, created_at, updated_at)
                    values (%s, %s, %s, %s, %s, %s, %s)
                    returning *
                    """,
                    (
                        message_id,
                        input_data["key"],
                        input_data["language"],
                        input_data["text"],
                        input_data["description"],
                        now,
                        now,
                    ),
                ).fetchone()
            except UniqueViolation as exc:
                raise duplicate_message_conflict(input_data) from exc
            record_audit(conn, caller.username, "message.created", "message", str(row["id"]), message_snapshot(row))
        log_message_event("message_created", "Message created", caller.username, row)
        response.headers["Location"] = f"/api/v1/messages/{message_id}"
        return to_message_response(row)

    @app.put("/api/v1/messages/{message_id}")
    def update_message(request: Request, message_id: str, body: Any = Body(None)) -> dict[str, Any]:
        caller = require_role(request, "admin")
        message_id = parse_uuid(message_id)
        input_data = validate_message_input(body)
        with transaction() as conn:
            existing = conn.execute("select 1 from messages where id = %s", (message_id,)).fetchone()
            if existing is None:
                raise NotFoundProblem()
            duplicate = conn.execute(
                "select 1 from messages where key = %s and language = %s and id <> %s",
                (input_data["key"], input_data["language"], message_id),
            ).fetchone()
            if duplicate:
                raise duplicate_message_conflict(input_data)
            try:
                row = conn.execute(
                    """
                    update messages
                    set key = %s, language = %s, text = %s, description = %s, updated_at = %s
                    where id = %s
                    returning *
                    """,
                    (
                        input_data["key"],
                        input_data["language"],
                        input_data["text"],
                        input_data["description"],
                        now_utc(),
                        message_id,
                    ),
                ).fetchone()
            except UniqueViolation as exc:
                raise duplicate_message_conflict(input_data) from exc
            record_audit(conn, caller.username, "message.updated", "message", str(row["id"]), message_snapshot(row))
        log_message_event("message_updated", "Message updated", caller.username, row)
        return to_message_response(row)

    @app.delete("/api/v1/messages/{message_id}", status_code=204)
    def delete_message(request: Request, message_id: str) -> Response:
        caller = require_role(request, "admin")
        message_id = parse_uuid(message_id)
        with transaction() as conn:
            row = conn.execute("delete from messages where id = %s returning *", (message_id,)).fetchone()
            if row is None:
                raise NotFoundProblem()
            record_audit(conn, caller.username, "message.deleted", "message", str(row["id"]), message_snapshot(row))
        log_message_event("message_deleted", "Message deleted", caller.username, row)
        return Response(status_code=204)


def to_bookmark_response(row: dict[str, Any]) -> dict[str, Any]:
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


def to_report_response(row: dict[str, Any]) -> dict[str, Any]:
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


def to_message_response(row: dict[str, Any]) -> dict[str, Any]:
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


def to_user_account_response(row: dict[str, Any]) -> dict[str, Any]:
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


def to_audit_response(row: dict[str, Any]) -> dict[str, Any]:
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


def page_of(rows: list[dict[str, Any]], page: int, size: int, total: int, mapper) -> dict[str, Any]:
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


def listing_where(caller: Any, filters: dict[str, Any]) -> tuple[str, tuple[Any, ...]]:
    conditions: list[str] = []
    params: tuple[Any, ...] = ()
    if filters["visibility"] == "public":
        conditions.append("visibility = 'public' and status = 'active'")
    else:
        if caller is None:
            from .problems import UnauthorizedProblem

            raise UnauthorizedProblem()
        conditions.append("owner = %s")
        params = (*params, caller.username)
        if filters["visibility"] is not None:
            conditions.append("visibility = %s")
            params = (*params, filters["visibility"])
    if filters["tags"]:
        conditions.append("tags @> %s::text[]")
        params = (*params, filters["tags"])
    if filters["q"] is not None and filters["q"].strip():
        conditions.append("(title ilike %s escape E'\\\\' or notes ilike %s escape E'\\\\')")
        pattern = f"%{escape_like(filters['q'])}%"
        params = (*params, pattern, pattern)
    return " and ".join(conditions), params


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
    validator.check(isinstance(resolution, str) and resolution in REPORT_STATUSES, "resolution", "validation.resolution.invalid")
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
        {"bookmarkId": str(report["bookmark_id"]), "resolution": resolution, "note": note, "autoResolved": auto_resolved},
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


WITH_BOOKMARK_COUNT = """
select u.*, (select count(*)::int from bookmarks b where b.owner = u.username) as bookmark_count
from user_accounts u
"""


def find_account(username: str) -> dict[str, Any] | None:
    return one(f"{WITH_BOOKMARK_COUNT} where u.username = %s", (username,))


def date_param(value: str | None, name: str) -> datetime | None:
    if value is None:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise BadRequestProblem(f"{name} must be an RFC 3339 date-time") from exc


def count(sql: str) -> int:
    return int(one(sql)["count"])


def count_per_day(table: str, column: str, start: datetime) -> dict[str, int]:
    rows = query(
        f"""
        select ({column} at time zone 'UTC')::date::text as day, count(*)::int as count
        from {table}
        where {column} >= %s
        group by day
        """,
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
    return ConflictProblem(f"A message with key '{input_data['key']}' and language '{input_data['language']}' already exists.")


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
