from __future__ import annotations

from typing import Any
from uuid import uuid4

from fastapi import APIRouter, Request, Response
from psycopg import sql

from ..api import (
    V1_BOOKMARKS_DEPRECATION,
    V1_BOOKMARKS_SUCCESSOR,
    V1_BOOKMARKS_SUNSET,
    find_bookmark,
    listing_where,
    owned_by_caller,
    page_of,
    parse_bookmark_filters,
    to_bookmark_response,
    validate_bookmark_input,
    visible_to,
)
from ..auth import CurrentCaller, OptionalCaller
from ..cursor import BookmarkCursor, decode_cursor, encode_cursor
from ..db import execute, one, query, transaction
from ..problems import ConflictProblem, NotFoundProblem, parse_uuid, require_valid_paging, single_param
from ..schemas import Bookmark, BookmarkCursorPage, BookmarkInput, BookmarkPage, TagList, body_payload
from ..time import now_utc

router = APIRouter()


@router.get("/api/v1/bookmarks", response_model=BookmarkPage, response_model_exclude_none=True)
def list_bookmarks_v1(request: Request, response: Response, caller: OptionalCaller) -> dict[str, Any]:
    response.headers["Deprecation"] = V1_BOOKMARKS_DEPRECATION
    response.headers["Sunset"] = V1_BOOKMARKS_SUNSET
    response.headers["Link"] = V1_BOOKMARKS_SUCCESSOR
    page, size = require_valid_paging(request)
    filters = parse_bookmark_filters(request)
    where, params = listing_where(caller, filters)
    items = query(
        sql.SQL(
            """
            select * from bookmarks where {where}
            order by created_at desc, id desc
            limit %s offset %s
            """
        ).format(where=where),
        (*params, size, page * size),
    )
    total = int(
        one(sql.SQL("select count(*)::int as count from bookmarks where {where}").format(where=where), params)["count"]
    )
    return page_of(items, page, size, total, to_bookmark_response)


@router.get("/api/v2/bookmarks", response_model=BookmarkCursorPage, response_model_exclude_none=True)
def list_bookmarks_v2(request: Request, caller: OptionalCaller) -> dict[str, Any]:
    _page, size = require_valid_paging(request)
    filters = parse_bookmark_filters(request)
    raw_cursor = single_param(request, "cursor")
    cursor = decode_cursor(raw_cursor) if raw_cursor is not None else None
    where, params = listing_where(caller, filters)
    cursor_clause = sql.SQL("")
    if cursor is not None:
        cursor_clause = sql.SQL(" and (created_at < %s or (created_at = %s and id < %s::uuid))")
        params = (*params, cursor.created_at, cursor.created_at, cursor.id)
    rows = query(
        sql.SQL(
            """
            select * from bookmarks where {where}{cursor_clause}
            order by created_at desc, id desc
            limit %s
            """
        ).format(where=where, cursor_clause=cursor_clause),
        (*params, size + 1),
    )
    items = rows[:size]
    payload: dict[str, Any] = {"items": [to_bookmark_response(row) for row in items]}
    if len(rows) > size and items:
        last = items[-1]
        payload["nextCursor"] = encode_cursor(BookmarkCursor(last["created_at"], str(last["id"])))
    return payload


@router.post("/api/v1/bookmarks", status_code=201, response_model=Bookmark, response_model_exclude_none=True)
def create_bookmark(response: Response, caller: CurrentCaller, body: BookmarkInput | None) -> dict[str, Any]:
    input_data = validate_bookmark_input(body_payload(body))
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


@router.get("/api/v1/bookmarks/{bookmark_id}", response_model=Bookmark, response_model_exclude_none=True)
def get_bookmark(bookmark_id: str, caller: OptionalCaller) -> dict[str, Any]:
    bookmark = find_bookmark(parse_uuid(bookmark_id))
    username = caller.username if caller else None
    if bookmark is None or not visible_to(bookmark, username):
        raise NotFoundProblem()
    return to_bookmark_response(bookmark)


@router.put("/api/v1/bookmarks/{bookmark_id}", response_model=Bookmark, response_model_exclude_none=True)
def update_bookmark(bookmark_id: str, caller: CurrentCaller, body: BookmarkInput | None) -> dict[str, Any]:
    parsed_bookmark_id = parse_uuid(bookmark_id)
    input_data = validate_bookmark_input(body_payload(body))
    with transaction() as conn:
        bookmark = conn.execute("select * from bookmarks where id = %s for update", (parsed_bookmark_id,)).fetchone()
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
                parsed_bookmark_id,
            ),
        ).fetchone()
    return to_bookmark_response(updated)


@router.delete("/api/v1/bookmarks/{bookmark_id}", status_code=204)
def delete_bookmark(bookmark_id: str, caller: CurrentCaller) -> Response:
    bookmark = owned_by_caller(caller.username, parse_uuid(bookmark_id))
    execute("delete from bookmarks where id = %s", (bookmark["id"],))
    return Response(status_code=204)


@router.get("/api/v1/tags", response_model=TagList)
def list_tags(caller: CurrentCaller) -> dict[str, Any]:
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
