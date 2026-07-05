from __future__ import annotations

import json
import os
import re
from typing import Any
from urllib import error, request

import schemathesis


RUN_ID = re.sub(r"[^a-z0-9-]", "-", os.getenv("STACKVERSE_SCHEMATHESIS_RUN_ID", "st-local").lower())
BACKEND_URL = os.getenv("BACKEND_URL", "http://localhost:8080").rstrip("/")
TOKEN = os.getenv("STACKVERSE_SCHEMATHESIS_TOKEN", "")
CLEANUP = os.getenv("STACKVERSE_SCHEMATHESIS_CLEANUP", "true").lower() not in {"0", "false", "no"}


@schemathesis.hook
def before_call(ctx: Any, case: Any, **kwargs: Any) -> None:
    add_auth_header(case)
    normalize_path_parameters(case)
    normalize_request_body(case)


@schemathesis.hook
def after_call(ctx: Any, case: Any, response: Any) -> None:
    if not CLEANUP or response.status_code != 201:
        return
    operation_id = get_operation_id(case)
    if operation_id == "createBookmark":
        cleanup_created(response, "/api/v1/bookmarks/{id}")
    elif operation_id == "createMessage":
        cleanup_created(response, "/api/v1/messages/{id}")


def add_auth_header(case: Any) -> None:
    if not TOKEN or is_explicitly_public(case):
        return
    if case.headers is None:
        case.headers = {}
    case.headers.setdefault("Authorization", f"Bearer {TOKEN}")


def is_explicitly_public(case: Any) -> bool:
    raw = get_operation_raw(case)
    return raw.get("security") == []


def normalize_path_parameters(case: Any) -> None:
    params = getattr(case, "path_parameters", None)
    if not isinstance(params, dict):
        return

    # Avoid blocking or otherwise mutating built-in dev users by chance.
    if "username" in params:
        params["username"] = f"schemathesis-missing-{RUN_ID}"


def normalize_request_body(case: Any) -> None:
    body = getattr(case, "body", None)
    if not isinstance(body, dict):
        return

    operation_id = get_operation_id(case)
    if operation_id in {"createBookmark", "updateBookmark"}:
        body["url"] = stable_http_url(body.get("url"))
        if "title" not in body or not isinstance(body["title"], str) or not body["title"]:
            body["title"] = f"Schemathesis {RUN_ID}"
    elif operation_id in {"createMessage", "updateMessage"}:
        body["key"] = stable_message_key(body.get("key"))
        if "language" not in body or not re.fullmatch(r"[a-z]{2}", str(body["language"])):
            body["language"] = "en"
        if "text" not in body or not isinstance(body["text"], str) or not body["text"]:
            body["text"] = f"Schemathesis {RUN_ID}"


def stable_http_url(value: Any) -> str:
    text = str(value) if value is not None else ""
    if text.startswith(("http://", "https://")) and len(text) <= 2000:
        return text
    slug = re.sub(r"[^a-z0-9-]", "-", text.lower()).strip("-")[:40] or "case"
    return f"https://schemathesis.stackverse.test/{RUN_ID}/{slug}"


def stable_message_key(value: Any) -> str:
    leaf = re.sub(r"[^a-z0-9-]", "-", str(value).lower()).strip("-")[:40] or "case"
    return f"schemathesis.{RUN_ID}.{leaf}"[:150].rstrip(".-")


def cleanup_created(response: Any, path_template: str) -> None:
    try:
        body = response.json()
    except (ValueError, json.JSONDecodeError, AttributeError):
        return
    resource_id = body.get("id") if isinstance(body, dict) else None
    if not isinstance(resource_id, str) or not resource_id:
        return
    delete(f"{BACKEND_URL}{path_template.format(id=resource_id)}")


def delete(url: str) -> None:
    headers = {"Authorization": f"Bearer {TOKEN}"} if TOKEN else {}
    req = request.Request(url, headers=headers, method="DELETE")
    try:
        request.urlopen(req, timeout=10).close()
    except (error.HTTPError, error.URLError):
        # Cleanup is best-effort; the original Schemathesis response is what the
        # test run validates and reports.
        return


def get_operation_id(case: Any) -> str:
    operation = getattr(case, "operation", None)
    for attr in ("operation_id", "operationId"):
        value = getattr(operation, attr, None)
        if isinstance(value, str):
            return value
    raw = get_operation_raw(case)
    value = raw.get("operationId")
    return value if isinstance(value, str) else ""


def get_operation_raw(case: Any) -> dict[str, Any]:
    operation = getattr(case, "operation", None)
    definition = getattr(operation, "definition", None)
    raw = getattr(definition, "raw", None)
    return raw if isinstance(raw, dict) else {}
