from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import psycopg
import uvicorn
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException as StarletteHTTPException

from .config import config
from .db import close_pool, open_pool, run_migrations
from .i18n import localize, resolve_language
from .logging_setup import log_event, logger
from .problems import AppProblem, ValidationProblem, first_param, problem_response
from .routers import register_routes
from .seed import seed_messages
from .telemetry import configure_telemetry, shutdown_telemetry


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    open_pool()
    run_migrations()
    seed_messages()
    log_event(
        "info",
        "application_start",
        "success",
        f"Stackverse backend (python-fastapi) listening on :{config.port}",
        port=config.port,
        db_host=config.db_host,
        db_port=config.db_port,
        db_name=config.db_name,
        oidc_issuer=config.oidc_issuer_uri,
        oidc_jwks_uri=config.oidc_jwks_uri or "(via OIDC discovery)",
        seed_messages_dir=str(config.seed_messages_dir),
        log_level=config.log_level,
        log_format=config.log_format,
        otel_enabled=config.otel_enabled,
    )
    try:
        yield
    finally:
        log_event("info", "application_stop", "success", "Stackverse backend shutting down")
        close_pool()
        shutdown_telemetry()


def build_app() -> FastAPI:
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    @app.exception_handler(ValidationProblem)
    async def validation_problem_handler(request: Request, exc: ValidationProblem):
        log_event(
            "info",
            "input_validation_failed",
            "failure",
            "Request validation failed",
            error_code="validation_failed",
            fields=",".join(violation.field for violation in exc.violations),
        )
        language = await run_in_threadpool(
            resolve_language,
            first_param(request, "lang"),
            request.headers.get("accept-language"),
        )
        errors = [
            {
                "field": violation.field,
                "messageKey": violation.message_key,
                "message": await run_in_threadpool(localize, violation.message_key, language),
            }
            for violation in exc.violations
        ]
        return problem_response(400, "Bad Request", "Request validation failed.", errors)

    @app.exception_handler(AppProblem)
    async def app_problem_handler(request: Request, exc: AppProblem):
        detail = exc.detail
        if exc.detail_key is not None:
            language = await run_in_threadpool(
                resolve_language,
                first_param(request, "lang"),
                request.headers.get("accept-language"),
            )
            detail = await run_in_threadpool(localize, exc.detail_key, language)
        return problem_response(exc.status, exc.title, detail)

    @app.exception_handler(RequestValidationError)
    async def request_validation_handler(request: Request, exc: RequestValidationError):
        log_event(
            "info",
            "input_validation_failed",
            "failure",
            "Request validation failed",
            error_code="request_validation_failed",
        )
        violations = _request_validation_violations(request.url.path, exc)
        if not violations:
            return problem_response(400, "Bad Request", "Request validation failed.")
        language = await run_in_threadpool(
            resolve_language,
            first_param(request, "lang"),
            request.headers.get("accept-language"),
        )
        errors = [
            {
                "field": field,
                "messageKey": message_key,
                "message": await run_in_threadpool(localize, message_key, language),
            }
            for field, message_key in violations
        ]
        return problem_response(400, "Bad Request", "Request validation failed.", errors)

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(_request: Request, exc: StarletteHTTPException):
        title = "Not Found" if exc.status_code == 404 else "Method Not Allowed" if exc.status_code == 405 else "Error"
        return problem_response(exc.status_code, title)

    @app.exception_handler(Exception)
    async def unhandled_handler(_request: Request, exc: Exception):
        if isinstance(exc, psycopg.Error):
            log_event(
                "error",
                "dependency_call_failed",
                "failure",
                "PostgreSQL call failed during a request",
                dependency="postgres",
                error_code=exc.__class__.__name__.lower(),
            )
        else:
            logger.exception("Unhandled error")
        return problem_response(500, "Internal Server Error", "An unexpected error occurred.")

    register_routes(app)
    configure_telemetry(app)
    return app


def _request_validation_violations(path: str, exc: RequestValidationError) -> list[tuple[str, str]]:
    violations: list[tuple[str, str]] = []
    for error in exc.errors():
        location = error.get("loc", ())
        if len(location) < 2 or location[0] != "body" or not isinstance(location[1], str):
            continue
        field = location[1]
        message_key = _structural_message_key(path, field)
        violation = (field, message_key) if message_key is not None else None
        if violation is not None and violation not in violations:
            violations.append(violation)
    return violations


def _structural_message_key(path: str, field: str) -> str | None:
    common = {
        "url": "validation.url.invalid",
        "title": "validation.title.required",
        "notes": "validation.notes.too-long",
        "tags": "validation.tag.invalid",
        "key": "validation.message.key.invalid",
        "language": "validation.message.language.invalid",
        "text": "validation.message.text.required",
        "description": "validation.message.description.too-long",
        "comment": "validation.report.comment.too-long",
        "resolution": "validation.resolution.invalid",
    }
    if field == "reason":
        return "validation.block.reason.required" if "/admin/users/" in path else "validation.report.reason.invalid"
    if field == "note":
        if "/admin/bookmarks/" in path:
            return "validation.bookmark-status.note.too-long"
        if "/admin/reports/" in path:
            return "validation.resolution.note.too-long"
    if field == "status" and "/admin/bookmarks/" in path:
        return "validation.bookmark-status.invalid"
    return common.get(field)


app = build_app()


def run() -> None:
    uvicorn.run("stackverse_backend.main:app", host="0.0.0.0", port=config.port, log_config=None)


if __name__ == "__main__":
    run()
