from __future__ import annotations

import base64
import hashlib
import json
from typing import Any

from django.http import HttpResponse


def response_with_etag(request: Any, payload: Any, extra_headers: dict[str, str] | None = None) -> HttpResponse:
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    digest = base64.urlsafe_b64encode(hashlib.sha256(body.encode("utf-8")).digest()).decode("ascii").rstrip("=")
    etag = f'"{digest}"'
    headers = {"ETag": etag, "Cache-Control": "no-cache", **(extra_headers or {})}
    if any(tag.strip() == etag for tag in request.headers.get("if-none-match", "").split(",")):
        response = HttpResponse(status=304)
        if response.has_header("Content-Type"):
            del response["Content-Type"]
    else:
        response = HttpResponse(body, content_type="application/json")
    for name, value in headers.items():
        response[name] = value
    return response
