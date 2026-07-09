from __future__ import annotations

import base64
import hashlib
import json

from fastapi import Request, Response

from .schemas import ContractModel


def response_with_etag(
    request: Request,
    payload: object,
    response_model: type[ContractModel],
    extra_headers: dict[str, str] | None = None,
) -> Response:
    serialized = response_model.model_validate(payload).model_dump(by_alias=True, exclude_none=True, mode="json")
    body = json.dumps(serialized, ensure_ascii=False, separators=(",", ":"))
    digest = base64.urlsafe_b64encode(hashlib.sha256(body.encode("utf-8")).digest()).decode("ascii").rstrip("=")
    etag = f'"{digest}"'
    headers = {"ETag": etag, "Cache-Control": "no-cache", **(extra_headers or {})}
    if any(tag.strip() == etag for tag in request.headers.get("if-none-match", "").split(",")):
        return Response(status_code=304, headers=headers)
    return Response(content=body, media_type="application/json", headers=headers)
