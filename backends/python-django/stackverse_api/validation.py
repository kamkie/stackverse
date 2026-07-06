from __future__ import annotations

import re
from datetime import datetime
from typing import Any
from urllib.parse import urlparse

from .problems import BadRequestProblem, Validator, multi_param, require_max_length, single_param

TAG_PATTERN = re.compile(r"^[a-z0-9-]{1,30}$")
KEY_PATTERN = re.compile(r"^[a-z0-9-]+(\.[a-z0-9-]+)*$")
LANGUAGE_PATTERN = re.compile(r"^[a-z]{2}$")
VISIBILITIES = {"private", "public"}
REPORT_REASONS = {"spam", "offensive", "broken-link", "other"}
REPORT_STATUSES = {"open", "dismissed", "actioned"}


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


def parse_bookmark_filters(request: Any) -> dict[str, Any]:
    q = single_param(request, "q")
    require_max_length(q, 200, "q")
    visibility = single_param(request, "visibility")
    if visibility is not None and visibility not in VISIBILITIES:
        raise BadRequestProblem(f"unknown visibility: {visibility}")
    return {"tags": validate_query_tags(multi_param(request, "tag")), "q": q, "visibility": visibility}


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


def validate_bookmark_status_input(body: Any) -> tuple[str, str | None]:
    input_data = body if isinstance(body, dict) else {}
    validator = Validator()
    status = input_data.get("status")
    validator.check(status in {"active", "hidden"}, "status", "validation.bookmark-status.invalid")
    note = input_data.get("note") if isinstance(input_data.get("note"), str) else None
    validator.check(len(note or "") <= 1000, "note", "validation.bookmark-status.note.too-long")
    validator.throw_if_invalid()
    return str(status), note


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


def date_param(value: str | None, name: str) -> datetime | None:
    if value is None:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise BadRequestProblem(f"{name} must be an RFC 3339 date-time") from exc
