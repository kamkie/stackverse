from __future__ import annotations

import base64
import hashlib
import json
from typing import Any

from fastapi import Request, Response


def response_with_etag(request: Request, payload: Any, extra_headers: dict[str, str] | None = None) -> Response:
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    digest = base64.urlsafe_b64encode(hashlib.sha256(body.encode("utf-8")).digest()).decode("ascii").rstrip("=")
    etag = f'"{digest}"'
    headers = {"ETag": etag, "Cache-Control": "no-cache", **(extra_headers or {})}
    if any(tag.strip() == etag for tag in request.headers.get("if-none-match", "").split(",")):
        return Response(status_code=304, headers=headers)
    return Response(content=body, media_type="application/json", headers=headers)
