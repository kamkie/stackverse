from __future__ import annotations

from typing import Any
from uuid import uuid4

from fastapi import APIRouter, Request, Response
from psycopg import sql
from psycopg.errors import UniqueViolation

from ..api import (
    hide_bookmark,
    own_report,
    page_of,
    require_open,
    resolve_one,
    to_report_response,
    validate_report_input,
    validate_resolution_input,
    validated_report_status,
    where_clause,
)
from ..audit import record_audit
from ..auth import CurrentCaller, ModeratorCaller
from ..db import one, query, transaction
from ..logging_setup import log_event
from ..problems import ConflictProblem, NotFoundProblem, parse_uuid, require_valid_paging, single_param
from ..schemas import Report, ReportInput, ReportPage, ReportResolutionInput, body_payload, problem_responses
from ..time import now_utc

router = APIRouter()


@router.post(
    "/api/v1/bookmarks/{bookmark_id}/reports",
    status_code=201,
    response_model=Report,
    response_model_exclude_none=True,
    responses=problem_responses(400, 401, 404, 409),
)
def report_bookmark(bookmark_id: str, caller: CurrentCaller, body: ReportInput) -> dict[str, Any]:
    parsed_bookmark_id = parse_uuid(bookmark_id)
    input_data = validate_report_input(body_payload(body))
    with transaction() as conn:
        bookmark = conn.execute(
            "select visibility, status from bookmarks where id = %s for update",
            (parsed_bookmark_id,),
        ).fetchone()
        if bookmark is None or bookmark["visibility"] != "public" or bookmark["status"] != "active":
            raise NotFoundProblem()
        open_report = conn.execute(
            "select 1 from reports where bookmark_id = %s and reporter = %s and status = 'open'",
            (parsed_bookmark_id, caller.username),
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
                (
                    str(uuid4()),
                    parsed_bookmark_id,
                    caller.username,
                    input_data["reason"],
                    input_data["comment"],
                    now_utc(),
                ),
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
        bookmark_id=parsed_bookmark_id,
        reason=row["reason"],
    )
    return to_report_response(row)


@router.get("/api/v1/reports", response_model=ReportPage, response_model_exclude_none=True)
def list_my_reports(request: Request, caller: CurrentCaller) -> dict[str, Any]:
    page, size = require_valid_paging(request)
    status = validated_report_status(single_param(request, "status"))
    conditions = [sql.SQL("reporter = %s")]
    params: tuple[Any, ...] = (caller.username,)
    if status is not None:
        conditions.append(sql.SQL("status = %s"))
        params = (*params, status)
    where = where_clause(conditions)
    rows = query(
        sql.SQL(
            """
            select * from reports where {where}
            order by created_at desc, id desc
            limit %s offset %s
            """
        ).format(where=where),
        (*params, size, page * size),
    )
    total = int(
        one(sql.SQL("select count(*)::int as count from reports where {where}").format(where=where), params)["count"]
    )
    return page_of(rows, page, size, total, to_report_response)


@router.put(
    "/api/v1/reports/{report_id}",
    response_model=Report,
    response_model_exclude_none=True,
    responses=problem_responses(400, 401, 404, 409),
)
def update_my_report(report_id: str, caller: CurrentCaller, body: ReportInput) -> dict[str, Any]:
    parsed_report_id = parse_uuid(report_id)
    input_data = validate_report_input(body_payload(body))
    with transaction() as conn:
        report = own_report(conn, caller.username, parsed_report_id)
        require_open(report)
        updated = conn.execute(
            "update reports set reason = %s, comment = %s where id = %s returning *",
            (input_data["reason"], input_data["comment"], parsed_report_id),
        ).fetchone()
    log_event(
        "info",
        "report_updated",
        "success",
        "Report updated by its reporter",
        actor=caller.username,
        resource_type="report",
        resource_id=parsed_report_id,
        bookmark_id=str(report["bookmark_id"]),
        reason=input_data["reason"],
    )
    return to_report_response(updated)


@router.delete("/api/v1/reports/{report_id}", status_code=204)
def withdraw_report(report_id: str, caller: CurrentCaller) -> Response:
    parsed_report_id = parse_uuid(report_id)
    with transaction() as conn:
        report = own_report(conn, caller.username, parsed_report_id)
        require_open(report)
        conn.execute("delete from reports where id = %s", (parsed_report_id,))
    log_event(
        "info",
        "report_withdrawn",
        "success",
        "Report withdrawn by its reporter",
        actor=caller.username,
        resource_type="report",
        resource_id=parsed_report_id,
        bookmark_id=str(report["bookmark_id"]),
    )
    return Response(status_code=204)


@router.get("/api/v1/admin/reports", response_model=ReportPage, response_model_exclude_none=True)
def list_reports(request: Request, _caller: ModeratorCaller) -> dict[str, Any]:
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


@router.put(
    "/api/v1/admin/reports/{report_id}",
    response_model=Report,
    response_model_exclude_none=True,
    responses=problem_responses(400, 401, 403, 404),
)
def resolve_report(report_id: str, caller: ModeratorCaller, body: ReportResolutionInput) -> dict[str, Any]:
    parsed_report_id = parse_uuid(report_id)
    target, note = validate_resolution_input(body_payload(body))
    with transaction() as conn:
        if target == "actioned":
            scalar = conn.execute("select bookmark_id from reports where id = %s", (parsed_report_id,)).fetchone()
            if scalar is None:
                raise NotFoundProblem()
            conn.execute("select id from bookmarks where id = %s for update", (scalar["bookmark_id"],))
        report = conn.execute("select * from reports where id = %s for update", (parsed_report_id,)).fetchone()
        if report is None:
            raise NotFoundProblem()
        if target == "open":
            conflict = conn.execute(
                """
                select 1 from reports
                where bookmark_id = %s and reporter = %s and status = 'open' and id <> %s
                """,
                (report["bookmark_id"], report["reporter"], parsed_report_id),
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
                    (parsed_report_id,),
                ).fetchone()
            except UniqueViolation as exc:
                raise ConflictProblem("The reporter already has another open report on this bookmark.") from exc
            record_audit(
                conn,
                caller.username,
                "report.reopened",
                "report",
                parsed_report_id,
                {"bookmarkId": str(report["bookmark_id"])},
            )
            log_event(
                "info",
                "report_reopened",
                "success",
                "Report re-opened",
                actor=caller.username,
                resource_type="report",
                resource_id=parsed_report_id,
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
                (report["bookmark_id"], parsed_report_id),
            ).fetchall()
            for sibling in siblings:
                resolve_one(conn, sibling, "actioned", caller.username, note, True)
    return to_report_response(resolved)
