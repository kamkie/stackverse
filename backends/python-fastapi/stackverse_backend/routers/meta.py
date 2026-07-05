from __future__ import annotations

from time import perf_counter

from fastapi import APIRouter, Response

from ..api import swap_readiness
from ..db import query
from ..logging_setup import log_event, logger

router = APIRouter()


@router.get("/healthz")
def healthz() -> Response:
    return Response(status_code=200)


@router.get("/readyz")
def readyz() -> Response:
    started_at = perf_counter()
    try:
        query("select 1")
        if swap_readiness(True):
            logger.info("Readiness restored: database reachable again")
        return Response(status_code=200)
    except Exception as exc:
        if swap_readiness(False):
            log_event(
                "warn",
                "dependency_call_failed",
                "failure",
                "Readiness lost: database unreachable",
                dependency="postgres",
                duration_ms=round((perf_counter() - started_at) * 1000),
                error_code=exc.__class__.__name__.lower(),
            )
        return Response(status_code=503)
