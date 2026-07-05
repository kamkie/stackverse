from __future__ import annotations

from typing import Any

from fastapi import APIRouter

from ..auth import CurrentCaller, me_response

router = APIRouter()


@router.get("/api/v1/me")
def me(caller: CurrentCaller) -> dict[str, Any]:
    return me_response(caller)
