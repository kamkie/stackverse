from __future__ import annotations

from typing import Any
from uuid import uuid4

from fastapi import APIRouter, Request, Response
from psycopg import sql
from psycopg.errors import UniqueViolation

from ..api import (
    duplicate_message_conflict,
    log_message_event,
    message_snapshot,
    page_of,
    to_message_response,
    validate_message_input,
    where_clause,
)
from ..audit import record_audit
from ..auth import AdminCaller, OptionalCaller
from ..db import one, query, transaction
from ..etag import response_with_etag
from ..i18n import message_bundle, resolve_language
from ..problems import (
    NotFoundProblem,
    escape_like,
    first_param,
    parse_uuid,
    require_max_length,
    require_valid_paging,
    single_param,
)
from ..schemas import Message, MessageBundle, MessageInput, MessagePage, body_payload
from ..time import now_utc

router = APIRouter()


@router.get("/api/v1/messages", response_model=MessagePage, response_model_exclude_none=True)
def list_messages(request: Request, _caller: OptionalCaller) -> Response:
    page, size = require_valid_paging(request)
    key = single_param(request, "key")
    language = single_param(request, "language")
    q = single_param(request, "q")
    require_max_length(q, 200, "q")
    conditions = [sql.SQL("true")]
    params: tuple[Any, ...] = ()
    if key is not None:
        conditions.append(sql.SQL("key = %s"))
        params = (*params, key)
    if language is not None:
        conditions.append(sql.SQL("language = %s"))
        params = (*params, language)
    if q is not None and q.strip():
        conditions.append(sql.SQL("(key ilike %s escape E'\\\\' or text ilike %s escape E'\\\\')"))
        pattern = f"%{escape_like(q)}%"
        params = (*params, pattern, pattern)
    where = where_clause(conditions)
    rows = query(
        sql.SQL("select * from messages where {where} order by key, language limit %s offset %s").format(where=where),
        (*params, size, page * size),
    )
    total = int(
        one(sql.SQL("select count(*)::int as count from messages where {where}").format(where=where), params)["count"]
    )
    return response_with_etag(request, page_of(rows, page, size, total, to_message_response))


@router.get("/api/v1/messages/bundle", response_model=MessageBundle)
def get_bundle(request: Request, _caller: OptionalCaller) -> Response:
    language = resolve_language(first_param(request, "lang"), request.headers.get("accept-language"))
    return response_with_etag(
        request,
        {"language": language, "messages": message_bundle(language)},
        {"Content-Language": language},
    )


@router.get("/api/v1/messages/{message_id}", response_model=Message, response_model_exclude_none=True)
def get_message(request: Request, message_id: str, _caller: OptionalCaller) -> Response:
    message = one("select * from messages where id = %s", (parse_uuid(message_id),))
    if message is None:
        raise NotFoundProblem()
    return response_with_etag(request, to_message_response(message))


@router.post("/api/v1/messages", status_code=201, response_model=Message, response_model_exclude_none=True)
def create_message(response: Response, caller: AdminCaller, body: MessageInput | None) -> dict[str, Any]:
    input_data = validate_message_input(body_payload(body))
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


@router.put("/api/v1/messages/{message_id}", response_model=Message, response_model_exclude_none=True)
def update_message(message_id: str, caller: AdminCaller, body: MessageInput | None) -> dict[str, Any]:
    parsed_message_id = parse_uuid(message_id)
    input_data = validate_message_input(body_payload(body))
    with transaction() as conn:
        existing = conn.execute("select 1 from messages where id = %s", (parsed_message_id,)).fetchone()
        if existing is None:
            raise NotFoundProblem()
        duplicate = conn.execute(
            "select 1 from messages where key = %s and language = %s and id <> %s",
            (input_data["key"], input_data["language"], parsed_message_id),
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
                    parsed_message_id,
                ),
            ).fetchone()
        except UniqueViolation as exc:
            raise duplicate_message_conflict(input_data) from exc
        record_audit(conn, caller.username, "message.updated", "message", str(row["id"]), message_snapshot(row))
    log_message_event("message_updated", "Message updated", caller.username, row)
    return to_message_response(row)


@router.delete("/api/v1/messages/{message_id}", status_code=204)
def delete_message(message_id: str, caller: AdminCaller) -> Response:
    parsed_message_id = parse_uuid(message_id)
    with transaction() as conn:
        row = conn.execute("delete from messages where id = %s returning *", (parsed_message_id,)).fetchone()
        if row is None:
            raise NotFoundProblem()
        record_audit(conn, caller.username, "message.deleted", "message", str(row["id"]), message_snapshot(row))
    log_message_event("message_deleted", "Message deleted", caller.username, row)
    return Response(status_code=204)
