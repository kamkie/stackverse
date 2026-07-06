from __future__ import annotations

from datetime import UTC, datetime, timedelta
from threading import Lock
from time import perf_counter
from typing import Any

from django.db import IntegrityError, connection, transaction
from django.db.models import Count, OuterRef, Q, Subquery, Value
from django.db.models.functions import Coalesce, TruncDate
from django.http import HttpResponse
from rest_framework.decorators import api_view, authentication_classes, permission_classes
from rest_framework.permissions import AllowAny

from .audit import record_audit
from .auth import Caller, me_response, require_caller, require_role
from .cursor import BookmarkCursor, decode_cursor, encode_cursor
from .etag import response_with_etag
from .i18n import message_bundle, resolve_language
from .logging_setup import log_event, logger
from .models import AuditEntry, Bookmark, Message, Report, UserAccount
from .problems import (
    BadRequestProblem,
    ConflictProblem,
    NotFoundProblem,
    first_param,
    json_response,
    parse_uuid,
    require_max_length,
    require_valid_paging,
    single_param,
)
from .representations import (
    page_of,
    to_audit_response,
    to_bookmark_response,
    to_message_response,
    to_report_response,
    to_user_account_response,
)
from .time import now_utc
from .validation import (
    date_param,
    parse_bookmark_filters,
    validate_bookmark_input,
    validate_bookmark_status_input,
    validate_message_input,
    validate_report_input,
    validate_resolution_input,
    validated_report_status,
)

V1_BOOKMARKS_DEPRECATION = "@1782864000"
V1_BOOKMARKS_SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT"
V1_BOOKMARKS_SUCCESSOR = '</api/v2/bookmarks>; rel="successor-version"'
_readiness_lock = Lock()
_was_ready = True


def _caller_or_none(request: Any) -> Caller | None:
    caller = getattr(request, "user", None)
    return caller if isinstance(caller, Caller) else None


def _bookmark_listing_queryset(caller: Caller | None, filters: dict[str, Any]):
    queryset = Bookmark.objects.all()
    if filters["visibility"] == "public":
        queryset = queryset.filter(visibility="public", status="active")
    else:
        if caller is None:
            from .problems import UnauthorizedProblem

            raise UnauthorizedProblem()
        queryset = queryset.filter(owner=caller.username)
        if filters["visibility"] is not None:
            queryset = queryset.filter(visibility=filters["visibility"])
    if filters["tags"]:
        queryset = queryset.filter(tags__contains=filters["tags"])
    if filters["q"] is not None and filters["q"].strip():
        queryset = queryset.filter(Q(title__icontains=filters["q"]) | Q(notes__icontains=filters["q"]))
    return queryset


def _visible_to(bookmark: Bookmark, username: str | None) -> bool:
    return bookmark.owner == username or (bookmark.visibility == "public" and bookmark.status == "active")


def _owned_by_caller(username: str, bookmark_id: str) -> Bookmark:
    bookmark = Bookmark.objects.filter(id=bookmark_id).first()
    if bookmark is None or bookmark.owner != username:
        raise NotFoundProblem()
    return bookmark


def _report_require_open(report: Report) -> None:
    if report.status != "open":
        raise ConflictProblem("The report has already been resolved.")


def _message_snapshot(message: Message) -> dict[str, Any]:
    return {
        "key": message.key,
        "language": message.language,
        "text": message.text,
        "description": message.description,
    }


def _duplicate_message_conflict(input_data: dict[str, Any]) -> ConflictProblem:
    return ConflictProblem(
        f"A message with key '{input_data['key']}' and language '{input_data['language']}' already exists."
    )


def _log_message_event(
    event: str, description: str, actor: str, message: Message, resource_id: str | None = None
) -> None:
    log_event(
        "info",
        event,
        "success",
        description,
        actor=actor,
        resource_type="message",
        resource_id=resource_id or str(message.id),
        message_key=message.key,
        language=message.language,
    )


def _resolve_one(report: Report, resolution: str, actor: str, note: str | None, auto_resolved: bool) -> Report:
    report.status = resolution
    report.resolved_by = actor
    report.resolved_at = now_utc()
    report.resolution_note = note
    report.save(update_fields=["status", "resolved_by", "resolved_at", "resolution_note"])
    record_audit(
        actor,
        "report.resolved",
        "report",
        str(report.id),
        {
            "bookmarkId": str(report.bookmark_id),
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
        resource_id=str(report.id),
        bookmark_id=str(report.bookmark_id),
        resolution=resolution,
        auto_resolved=auto_resolved,
    )
    return report


def _hide_bookmark(actor: str, bookmark_id: str, note: str | None) -> None:
    bookmark = Bookmark.objects.select_for_update().filter(id=bookmark_id).first()
    if bookmark is None:
        raise NotFoundProblem()
    if bookmark.status == "hidden":
        return
    old_status = bookmark.status
    bookmark.status = "hidden"
    bookmark.updated_at = now_utc()
    bookmark.save(update_fields=["status", "updated_at"])
    record_audit(
        actor,
        "bookmark.status-changed",
        "bookmark",
        bookmark_id,
        {"from": old_status, "to": "hidden", "note": note},
    )
    log_event(
        "info",
        "bookmark_status_changed",
        "success",
        "Bookmark hidden by an actioned report",
        actor=actor,
        resource_type="bookmark",
        resource_id=bookmark_id,
        **{"from": old_status, "to": "hidden"},
    )


def _account_with_count(username: str) -> tuple[UserAccount, int]:
    account = UserAccount.objects.filter(username=username).first()
    if account is None:
        raise NotFoundProblem()
    return account, Bookmark.objects.filter(owner=username).count()


def _tag_counts_for_owner(username: str) -> list[dict[str, Any]]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            select tag, count(*)::int as count
            from bookmarks, unnest(tags) as tag
            where owner = %s
            group by tag
            order by count desc, tag asc
            """,
            [username],
        )
        return [{"tag": row[0], "count": int(row[1])} for row in cursor.fetchall()]


def _top_tags() -> list[dict[str, Any]]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            select tag, count(*)::int as count
            from bookmarks, unnest(tags) as tag
            group by tag
            order by count desc, tag asc
            limit 10
            """
        )
        return [{"tag": row[0], "count": int(row[1])} for row in cursor.fetchall()]


def _swap_readiness(ready: bool) -> bool:
    global _was_ready
    with _readiness_lock:
        previous = _was_ready
        if previous == ready:
            return False
        _was_ready = ready
        return True


@api_view(["GET"])
@authentication_classes([])
@permission_classes([AllowAny])
def healthz(_request: Any) -> HttpResponse:
    return HttpResponse(status=200)


@api_view(["GET"])
@authentication_classes([])
@permission_classes([AllowAny])
def readyz(_request: Any) -> HttpResponse:
    started_at = perf_counter()
    try:
        with connection.cursor() as cursor:
            cursor.execute("select 1")
        if _swap_readiness(True):
            logger.info("Readiness restored: database reachable again")
        return HttpResponse(status=200)
    except Exception as exc:
        if _swap_readiness(False):
            log_event(
                "warn",
                "dependency_call_failed",
                "failure",
                "Readiness lost: database unreachable",
                dependency="postgres",
                duration_ms=round((perf_counter() - started_at) * 1000),
                error_code=exc.__class__.__name__.lower(),
            )
        return HttpResponse(status=503)


@api_view(["GET", "POST"])
def bookmarks_v1(request: Any) -> HttpResponse:
    if request.method == "GET":
        page, size = require_valid_paging(request)
        filters = parse_bookmark_filters(request)
        queryset = _bookmark_listing_queryset(_caller_or_none(request), filters).order_by("-created_at", "-id")
        total = queryset.count()
        items = list(queryset[page * size : page * size + size])
        return json_response(
            page_of(items, page, size, total, to_bookmark_response),
            headers={
                "Deprecation": V1_BOOKMARKS_DEPRECATION,
                "Sunset": V1_BOOKMARKS_SUNSET,
                "Link": V1_BOOKMARKS_SUCCESSOR,
            },
        )

    caller = require_caller(request)
    input_data = validate_bookmark_input(request.data)
    timestamp = now_utc()
    bookmark = Bookmark.objects.create(
        owner=caller.username,
        url=input_data["url"],
        title=input_data["title"],
        notes=input_data["notes"],
        tags=input_data["tags"],
        visibility=input_data["visibility"],
        status="active",
        created_at=timestamp,
        updated_at=timestamp,
    )
    return json_response(
        to_bookmark_response(bookmark), status=201, headers={"Location": f"/api/v1/bookmarks/{bookmark.id}"}
    )


@api_view(["GET"])
def bookmarks_v2(request: Any) -> HttpResponse:
    _page, size = require_valid_paging(request)
    filters = parse_bookmark_filters(request)
    cursor_value = single_param(request, "cursor")
    bookmark_cursor = decode_cursor(cursor_value) if cursor_value is not None else None
    queryset = _bookmark_listing_queryset(_caller_or_none(request), filters)
    if bookmark_cursor is not None:
        queryset = queryset.filter(
            Q(created_at__lt=bookmark_cursor.created_at)
            | Q(created_at=bookmark_cursor.created_at, id__lt=bookmark_cursor.id)
        )
    rows = list(queryset.order_by("-created_at", "-id")[: size + 1])
    items = rows[:size]
    payload: dict[str, Any] = {"items": [to_bookmark_response(bookmark) for bookmark in items]}
    if len(rows) > size and items:
        last = items[-1]
        payload["nextCursor"] = encode_cursor(BookmarkCursor(last.created_at, str(last.id)))
    return json_response(payload)


@api_view(["GET", "PUT", "DELETE"])
def bookmark_detail(request: Any, bookmark_id: str) -> HttpResponse:
    parsed_bookmark_id = parse_uuid(bookmark_id)
    if request.method == "GET":
        bookmark = Bookmark.objects.filter(id=parsed_bookmark_id).first()
        caller = _caller_or_none(request)
        username = caller.username if caller else None
        if bookmark is None or not _visible_to(bookmark, username):
            raise NotFoundProblem()
        return json_response(to_bookmark_response(bookmark))

    caller = require_caller(request)
    if request.method == "DELETE":
        bookmark = _owned_by_caller(caller.username, parsed_bookmark_id)
        bookmark.delete()
        return HttpResponse(status=204)

    input_data = validate_bookmark_input(request.data)
    with transaction.atomic():
        bookmark = Bookmark.objects.select_for_update().filter(id=parsed_bookmark_id).first()
        if bookmark is None or bookmark.owner != caller.username:
            raise NotFoundProblem()
        if bookmark.status == "hidden" and input_data["visibility"] == "public":
            raise ConflictProblem(
                "This bookmark was hidden by moderation and cannot be made public.",
                "error.bookmark.hidden-publish",
            )
        bookmark.url = input_data["url"]
        bookmark.title = input_data["title"]
        bookmark.notes = input_data["notes"]
        bookmark.tags = input_data["tags"]
        bookmark.visibility = input_data["visibility"]
        bookmark.updated_at = now_utc()
        bookmark.save(update_fields=["url", "title", "notes", "tags", "visibility", "updated_at"])
    return json_response(to_bookmark_response(bookmark))


@api_view(["GET"])
def tags(request: Any) -> HttpResponse:
    caller = require_caller(request)
    return json_response({"tags": _tag_counts_for_owner(caller.username)})


@api_view(["GET"])
def me(request: Any) -> HttpResponse:
    return json_response(me_response(require_caller(request)))


@api_view(["POST"])
def report_bookmark(request: Any, bookmark_id: str) -> HttpResponse:
    caller = require_caller(request)
    parsed_bookmark_id = parse_uuid(bookmark_id)
    input_data = validate_report_input(request.data)
    with transaction.atomic():
        bookmark = Bookmark.objects.select_for_update().filter(id=parsed_bookmark_id).first()
        if bookmark is None or bookmark.visibility != "public" or bookmark.status != "active":
            raise NotFoundProblem()
        if Report.objects.filter(bookmark_id=parsed_bookmark_id, reporter=caller.username, status="open").exists():
            raise ConflictProblem("You already have an open report on this bookmark.")
        try:
            report = Report.objects.create(
                bookmark_id=parsed_bookmark_id,
                reporter=caller.username,
                reason=input_data["reason"],
                comment=input_data["comment"],
                status="open",
                created_at=now_utc(),
            )
        except IntegrityError as exc:
            raise ConflictProblem("You already have an open report on this bookmark.") from exc
    log_event(
        "info",
        "report_created",
        "success",
        "Report created on a public bookmark",
        actor=caller.username,
        resource_type="report",
        resource_id=str(report.id),
        bookmark_id=parsed_bookmark_id,
        reason=report.reason,
    )
    return json_response(to_report_response(report), status=201)


@api_view(["GET"])
def my_reports(request: Any) -> HttpResponse:
    caller = require_caller(request)
    page, size = require_valid_paging(request)
    status = validated_report_status(single_param(request, "status"))
    queryset = Report.objects.filter(reporter=caller.username)
    if status is not None:
        queryset = queryset.filter(status=status)
    queryset = queryset.order_by("-created_at", "-id")
    total = queryset.count()
    items = list(queryset[page * size : page * size + size])
    return json_response(page_of(items, page, size, total, to_report_response))


@api_view(["PUT", "DELETE"])
def my_report_detail(request: Any, report_id: str) -> HttpResponse:
    caller = require_caller(request)
    parsed_report_id = parse_uuid(report_id)
    with transaction.atomic():
        report = Report.objects.select_for_update().filter(id=parsed_report_id).first()
        if report is None or report.reporter != caller.username:
            raise NotFoundProblem()
        _report_require_open(report)
        if request.method == "DELETE":
            report.delete()
            log_event(
                "info",
                "report_withdrawn",
                "success",
                "Report withdrawn by its reporter",
                actor=caller.username,
                resource_type="report",
                resource_id=parsed_report_id,
                bookmark_id=str(report.bookmark_id),
            )
            return HttpResponse(status=204)
        input_data = validate_report_input(request.data)
        report.reason = input_data["reason"]
        report.comment = input_data["comment"]
        report.save(update_fields=["reason", "comment"])
    log_event(
        "info",
        "report_updated",
        "success",
        "Report updated by its reporter",
        actor=caller.username,
        resource_type="report",
        resource_id=parsed_report_id,
        bookmark_id=str(report.bookmark_id),
        reason=report.reason,
    )
    return json_response(to_report_response(report))


@api_view(["GET"])
def admin_reports(request: Any) -> HttpResponse:
    require_role(request, "moderator")
    page, size = require_valid_paging(request)
    status = validated_report_status(single_param(request, "status")) or "open"
    queryset = Report.objects.filter(status=status).order_by("created_at", "id")
    total = queryset.count()
    items = list(queryset[page * size : page * size + size])
    return json_response(page_of(items, page, size, total, to_report_response))


@api_view(["PUT"])
def admin_report_detail(request: Any, report_id: str) -> HttpResponse:
    caller = require_role(request, "moderator")
    parsed_report_id = parse_uuid(report_id)
    target, note = validate_resolution_input(request.data)
    with transaction.atomic():
        if target == "actioned":
            existing = Report.objects.only("bookmark_id").filter(id=parsed_report_id).first()
            if existing is None:
                raise NotFoundProblem()
            Bookmark.objects.select_for_update().filter(id=existing.bookmark_id).first()
        report = Report.objects.select_for_update().filter(id=parsed_report_id).first()
        if report is None:
            raise NotFoundProblem()
        if target == "open":
            if (
                Report.objects.filter(bookmark_id=report.bookmark_id, reporter=report.reporter, status="open")
                .exclude(id=parsed_report_id)
                .exists()
            ):
                raise ConflictProblem("The reporter already has another open report on this bookmark.")
            try:
                report.status = "open"
                report.resolved_by = None
                report.resolved_at = None
                report.resolution_note = None
                report.save(update_fields=["status", "resolved_by", "resolved_at", "resolution_note"])
            except IntegrityError as exc:
                raise ConflictProblem("The reporter already has another open report on this bookmark.") from exc
            record_audit(
                caller.username,
                "report.reopened",
                "report",
                str(report.id),
                {"bookmarkId": str(report.bookmark_id)},
            )
            log_event(
                "info",
                "report_reopened",
                "success",
                "Report re-opened",
                actor=caller.username,
                resource_type="report",
                resource_id=parsed_report_id,
                bookmark_id=str(report.bookmark_id),
            )
            return json_response(to_report_response(report))

        resolved = _resolve_one(report, target, caller.username, note, False)
        if target == "actioned":
            _hide_bookmark(caller.username, str(report.bookmark_id), note)
            siblings = (
                Report.objects.select_for_update()
                .filter(bookmark_id=report.bookmark_id, status="open")
                .exclude(id=parsed_report_id)
                .order_by("id")
            )
            for sibling in siblings:
                _resolve_one(sibling, "actioned", caller.username, note, True)
    return json_response(to_report_response(resolved))


@api_view(["PUT"])
def admin_bookmark_status(request: Any, bookmark_id: str) -> HttpResponse:
    caller = require_role(request, "moderator")
    parsed_bookmark_id = parse_uuid(bookmark_id)
    status, note = validate_bookmark_status_input(request.data)
    with transaction.atomic():
        bookmark = Bookmark.objects.select_for_update().filter(id=parsed_bookmark_id).first()
        if bookmark is None:
            raise NotFoundProblem()
        old_status = bookmark.status
        bookmark.status = status
        bookmark.updated_at = now_utc()
        bookmark.save(update_fields=["status", "updated_at"])
        record_audit(
            caller.username,
            "bookmark.status-changed",
            "bookmark",
            parsed_bookmark_id,
            {"from": old_status, "to": status, "note": note},
        )
    log_event(
        "info",
        "bookmark_status_changed",
        "success",
        "Bookmark moderation status changed",
        actor=caller.username,
        resource_type="bookmark",
        resource_id=parsed_bookmark_id,
        **{"from": old_status, "to": status},
    )
    return json_response(to_bookmark_response(bookmark))


@api_view(["GET"])
def admin_users(request: Any) -> HttpResponse:
    require_role(request, "admin")
    page, size = require_valid_paging(request)
    q = single_param(request, "q")
    require_max_length(q, 100, "q")
    status = single_param(request, "status")
    if status is not None and status not in {"active", "blocked"}:
        raise BadRequestProblem(f"unknown status: {status}")
    bookmark_count = (
        Bookmark.objects.filter(owner=OuterRef("username")).values("owner").annotate(count=Count("*")).values("count")
    )
    queryset = UserAccount.objects.annotate(bookmark_count=Coalesce(Subquery(bookmark_count), Value(0)))
    if q is not None and q.strip():
        queryset = queryset.filter(username__icontains=q)
    if status is not None:
        queryset = queryset.filter(status=status)
    queryset = queryset.order_by("-last_seen", "username")
    total = queryset.count()
    items = list(queryset[page * size : page * size + size])
    return json_response(
        page_of(items, page, size, total, lambda account: to_user_account_response(account, account.bookmark_count))
    )


@api_view(["GET"])
def admin_user_detail(request: Any, username: str) -> HttpResponse:
    require_role(request, "admin")
    account, bookmark_count = _account_with_count(username)
    return json_response(to_user_account_response(account, bookmark_count))


@api_view(["PUT"])
def admin_user_status(request: Any, username: str) -> HttpResponse:
    caller = require_role(request, "admin")
    input_data = request.data if isinstance(request.data, dict) else {}
    status = input_data.get("status")
    if status not in {"active", "blocked"}:
        raise BadRequestProblem("status is required")
    reason = input_data.get("reason").strip() if isinstance(input_data.get("reason"), str) else None
    if status == "blocked":
        from .problems import Validator

        validator = Validator()
        validator.check(reason is not None and reason != "", "reason", "validation.block.reason.required")
        validator.check(len(reason or "") <= 1000, "reason", "validation.block.reason.too-long")
        validator.throw_if_invalid()
        if username == caller.username:
            raise ConflictProblem("Admins cannot block themselves.")
    with transaction.atomic():
        account = UserAccount.objects.select_for_update().filter(username=username).first()
        if account is None:
            raise NotFoundProblem()
        if status == "blocked":
            account.status = "blocked"
            account.blocked_reason = reason
            account.save(update_fields=["status", "blocked_reason"])
            record_audit(caller.username, "user.blocked", "user", username, {"reason": reason})
        else:
            account.status = "active"
            account.blocked_reason = None
            account.save(update_fields=["status", "blocked_reason"])
            record_audit(caller.username, "user.unblocked", "user", username)
    log_event(
        "info",
        "user_blocked" if status == "blocked" else "user_unblocked",
        "success",
        "User account blocked" if status == "blocked" else "User account unblocked",
        actor=caller.username,
        resource_type="user",
        resource_id=username,
    )
    account, bookmark_count = _account_with_count(username)
    return json_response(to_user_account_response(account, bookmark_count))


@api_view(["GET"])
def audit_log(request: Any) -> HttpResponse:
    require_role(request, "admin")
    page, size = require_valid_paging(request)
    queryset = AuditEntry.objects.all()
    for parameter, field in (
        ("actor", "actor"),
        ("action", "action"),
        ("targetType", "target_type"),
        ("targetId", "target_id"),
    ):
        value = single_param(request, parameter)
        if value is not None:
            queryset = queryset.filter(**{field: value})
    from_value = date_param(single_param(request, "from"), "from")
    if from_value is not None:
        queryset = queryset.filter(created_at__gte=from_value)
    to_value = date_param(single_param(request, "to"), "to")
    if to_value is not None:
        queryset = queryset.filter(created_at__lte=to_value)
    queryset = queryset.order_by("-created_at", "-id")
    total = queryset.count()
    items = list(queryset[page * size : page * size + size])
    return json_response(page_of(items, page, size, total, to_audit_response))


@api_view(["GET"])
def admin_stats(request: Any) -> HttpResponse:
    require_role(request, "moderator")
    today = datetime.now(UTC).replace(hour=0, minute=0, second=0, microsecond=0)
    start = today - timedelta(days=29)
    created_rows = (
        Bookmark.objects.filter(created_at__gte=start)
        .annotate(day=TruncDate("created_at", tzinfo=UTC))
        .values("day")
        .annotate(count=Count("id"))
    )
    active_rows = (
        UserAccount.objects.filter(last_seen__gte=start)
        .annotate(day=TruncDate("last_seen", tzinfo=UTC))
        .values("day")
        .annotate(count=Count("username"))
    )
    created_per_day = {row["day"].isoformat(): int(row["count"]) for row in created_rows}
    active_per_day = {row["day"].isoformat(): int(row["count"]) for row in active_rows}
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
            "users": UserAccount.objects.count(),
            "bookmarks": Bookmark.objects.count(),
            "publicBookmarks": Bookmark.objects.filter(visibility="public").count(),
            "hiddenBookmarks": Bookmark.objects.filter(status="hidden").count(),
            "openReports": Report.objects.filter(status="open").count(),
        },
        "daily": daily,
        "topTags": _top_tags(),
    }
    return response_with_etag(request, payload)


@api_view(["GET", "POST"])
def messages(request: Any) -> HttpResponse:
    if request.method == "GET":
        page, size = require_valid_paging(request)
        key = single_param(request, "key")
        language = single_param(request, "language")
        q = single_param(request, "q")
        require_max_length(q, 200, "q")
        queryset = Message.objects.all()
        if key is not None:
            queryset = queryset.filter(key=key)
        if language is not None:
            queryset = queryset.filter(language=language)
        if q is not None and q.strip():
            queryset = queryset.filter(Q(key__icontains=q) | Q(text__icontains=q))
        queryset = queryset.order_by("key", "language")
        total = queryset.count()
        items = list(queryset[page * size : page * size + size])
        return response_with_etag(request, page_of(items, page, size, total, to_message_response))

    caller = require_role(request, "admin")
    input_data = validate_message_input(request.data)
    timestamp = now_utc()
    with transaction.atomic():
        if Message.objects.filter(key=input_data["key"], language=input_data["language"]).exists():
            raise _duplicate_message_conflict(input_data)
        try:
            message = Message.objects.create(
                key=input_data["key"],
                language=input_data["language"],
                text=input_data["text"],
                description=input_data["description"],
                created_at=timestamp,
                updated_at=timestamp,
            )
        except IntegrityError as exc:
            raise _duplicate_message_conflict(input_data) from exc
        record_audit(caller.username, "message.created", "message", str(message.id), _message_snapshot(message))
    _log_message_event("message_created", "Message created", caller.username, message)
    return json_response(
        to_message_response(message), status=201, headers={"Location": f"/api/v1/messages/{message.id}"}
    )


@api_view(["GET"])
def message_bundle_view(request: Any) -> HttpResponse:
    language = resolve_language(first_param(request, "lang"), request.headers.get("accept-language"))
    return response_with_etag(
        request,
        {"language": language, "messages": message_bundle(language)},
        {"Content-Language": language},
    )


@api_view(["GET", "PUT", "DELETE"])
def message_detail(request: Any, message_id: str) -> HttpResponse:
    parsed_message_id = parse_uuid(message_id)
    if request.method == "GET":
        message = Message.objects.filter(id=parsed_message_id).first()
        if message is None:
            raise NotFoundProblem()
        return response_with_etag(request, to_message_response(message))

    caller = require_role(request, "admin")
    if request.method == "DELETE":
        with transaction.atomic():
            message = Message.objects.filter(id=parsed_message_id).first()
            if message is None:
                raise NotFoundProblem()
            deleted_message_id = str(message.id)
            snapshot = _message_snapshot(message)
            message.delete()
            record_audit(caller.username, "message.deleted", "message", parsed_message_id, snapshot)
        _log_message_event("message_deleted", "Message deleted", caller.username, message, deleted_message_id)
        return HttpResponse(status=204)

    input_data = validate_message_input(request.data)
    with transaction.atomic():
        message = Message.objects.select_for_update().filter(id=parsed_message_id).first()
        if message is None:
            raise NotFoundProblem()
        if (
            Message.objects.filter(key=input_data["key"], language=input_data["language"])
            .exclude(id=parsed_message_id)
            .exists()
        ):
            raise _duplicate_message_conflict(input_data)
        try:
            message.key = input_data["key"]
            message.language = input_data["language"]
            message.text = input_data["text"]
            message.description = input_data["description"]
            message.updated_at = now_utc()
            message.save(update_fields=["key", "language", "text", "description", "updated_at"])
        except IntegrityError as exc:
            raise _duplicate_message_conflict(input_data) from exc
        record_audit(caller.username, "message.updated", "message", str(message.id), _message_snapshot(message))
    _log_message_event("message_updated", "Message updated", caller.username, message)
    return json_response(to_message_response(message))
