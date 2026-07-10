from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import APIRouter, Request, Response
from psycopg import sql

from ..api import (
    WITH_BOOKMARK_COUNT,
    count_per_day,
    date_param,
    find_account,
    page_of,
    to_audit_response,
    to_bookmark_response,
    to_user_account_response,
    validate_bookmark_status_input,
    where_clause,
)
from ..audit import record_audit
from ..auth import AdminCaller, ModeratorCaller
from ..db import one, query, transaction
from ..etag import response_with_etag
from ..logging_setup import log_event
from ..problems import (
    BadRequestProblem,
    ConflictProblem,
    NotFoundProblem,
    Validator,
    escape_like,
    parse_uuid,
    require_max_length,
    require_valid_paging,
    single_param,
)
from ..schemas import (
    AdminStats,
    AuditPage,
    Bookmark,
    BookmarkStatusInput,
    UserAccount,
    UserAccountPage,
    UserStatusInput,
    body_payload,
    problem_responses,
)
from ..time import now_utc

router = APIRouter()

AUDIT_FILTERS = (
    ("actor", sql.SQL("actor = %s")),
    ("action", sql.SQL("action = %s")),
    ("targetType", sql.SQL("target_type = %s")),
    ("targetId", sql.SQL("target_id = %s")),
)


@router.put(
    "/api/v1/admin/bookmarks/{bookmark_id}/status",
    response_model=Bookmark,
    response_model_exclude_none=True,
    responses=problem_responses(400, 401, 403, 404),
)
def set_bookmark_status(bookmark_id: str, caller: ModeratorCaller, body: BookmarkStatusInput) -> dict[str, Any]:
    parsed_bookmark_id = parse_uuid(bookmark_id)
    status, note = validate_bookmark_status_input(body_payload(body))
    with transaction() as conn:
        bookmark = conn.execute("select * from bookmarks where id = %s for update", (parsed_bookmark_id,)).fetchone()
        if bookmark is None:
            raise NotFoundProblem()
        updated = conn.execute(
            "update bookmarks set status = %s, updated_at = %s where id = %s returning *",
            (status, now_utc(), parsed_bookmark_id),
        ).fetchone()
        record_audit(
            conn,
            caller.username,
            "bookmark.status-changed",
            "bookmark",
            parsed_bookmark_id,
            {"from": bookmark["status"], "to": status, "note": note},
        )
    log_event(
        "info",
        "bookmark_status_changed",
        "success",
        "Bookmark moderation status changed",
        actor=caller.username,
        resource_type="bookmark",
        resource_id=parsed_bookmark_id,
        **{"from": bookmark["status"], "to": status},
    )
    return to_bookmark_response(updated)


@router.get("/api/v1/admin/users", response_model=UserAccountPage, response_model_exclude_none=True)
def list_users(request: Request, _caller: AdminCaller) -> dict[str, Any]:
    page, size = require_valid_paging(request)
    q = single_param(request, "q")
    require_max_length(q, 100, "q")
    status = single_param(request, "status")
    if status is not None and status not in {"active", "blocked"}:
        raise BadRequestProblem(f"unknown status: {status}")
    conditions = [sql.SQL("true")]
    params: tuple[Any, ...] = ()
    if q is not None and q.strip():
        conditions.append(sql.SQL("u.username ilike %s escape E'\\\\'"))
        params = (*params, f"%{escape_like(q)}%")
    if status is not None:
        conditions.append(sql.SQL("u.status = %s"))
        params = (*params, status)
    where = where_clause(conditions)
    rows = query(
        sql.SQL(
            """
            {with_bookmark_count} where {where}
            order by u.last_seen desc, u.username asc
            limit %s offset %s
            """
        ).format(with_bookmark_count=WITH_BOOKMARK_COUNT, where=where),
        (*params, size, page * size),
    )
    total = int(
        one(sql.SQL("select count(*)::int as count from user_accounts u where {where}").format(where=where), params)[
            "count"
        ]
    )
    return page_of(rows, page, size, total, to_user_account_response)


@router.get("/api/v1/admin/users/{username}", response_model=UserAccount, response_model_exclude_none=True)
def get_user(username: str, _caller: AdminCaller) -> dict[str, Any]:
    account = find_account(username)
    if account is None:
        raise NotFoundProblem()
    return to_user_account_response(account)


@router.put(
    "/api/v1/admin/users/{username}/status",
    response_model=UserAccount,
    response_model_exclude_none=True,
    responses=problem_responses(400, 401, 403, 404, 409),
)
def set_user_status(username: str, caller: AdminCaller, body: UserStatusInput) -> dict[str, Any]:
    input_data = body_payload(body) or {}
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
        existing = conn.execute(
            "select username from user_accounts where username = %s for update", (username,)
        ).fetchone()
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


@router.get("/api/v1/admin/audit-log", response_model=AuditPage, response_model_exclude_none=True)
def audit_log(request: Request, _caller: AdminCaller) -> dict[str, Any]:
    page, size = require_valid_paging(request)
    conditions = [sql.SQL("true")]
    params: tuple[Any, ...] = ()
    for parameter, condition in AUDIT_FILTERS:
        value = single_param(request, parameter)
        if value is not None:
            conditions.append(condition)
            params = (*params, value)
    from_value = date_param(single_param(request, "from"), "from")
    if from_value is not None:
        conditions.append(sql.SQL("created_at >= %s"))
        params = (*params, from_value)
    to_value = date_param(single_param(request, "to"), "to")
    if to_value is not None:
        conditions.append(sql.SQL("created_at <= %s"))
        params = (*params, to_value)
    where = where_clause(conditions)
    rows = query(
        sql.SQL(
            """
            select * from audit_entries where {where}
            order by created_at desc, id desc
            limit %s offset %s
            """
        ).format(where=where),
        (*params, size, page * size),
    )
    total = int(
        one(sql.SQL("select count(*)::int as count from audit_entries where {where}").format(where=where), params)[
            "count"
        ]
    )
    return page_of(rows, page, size, total, to_audit_response)


@router.get("/api/v1/admin/stats", response_model=AdminStats)
def stats(request: Request, _caller: ModeratorCaller) -> Response:
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
    totals = one(
        """
        select
            (select count(*)::int from user_accounts) as users,
            (select count(*)::int from bookmarks) as bookmarks,
            (select count(*)::int from bookmarks where visibility = 'public') as public_bookmarks,
            (select count(*)::int from bookmarks where status = 'hidden') as hidden_bookmarks,
            (select count(*)::int from reports where status = 'open') as open_reports
        """
    )
    payload = {
        "totals": {
            "users": int(totals["users"]),
            "bookmarks": int(totals["bookmarks"]),
            "publicBookmarks": int(totals["public_bookmarks"]),
            "hiddenBookmarks": int(totals["hidden_bookmarks"]),
            "openReports": int(totals["open_reports"]),
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
    return response_with_etag(request, payload, AdminStats)
