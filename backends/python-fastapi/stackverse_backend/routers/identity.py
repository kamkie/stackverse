from __future__ import annotations

from typing import Any

from fastapi import APIRouter

from ..auth import CurrentCaller, me_response
from ..schemas import CurrentUser

router = APIRouter()


@router.get("/api/v1/me", response_model=CurrentUser, response_model_exclude_none=True)
def me(caller: CurrentCaller) -> dict[str, Any]:
    return me_response(caller)
