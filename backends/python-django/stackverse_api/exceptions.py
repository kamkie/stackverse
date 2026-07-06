from __future__ import annotations

from typing import Any

from django.db import DatabaseError
from django.http import Http404
from rest_framework.exceptions import MethodNotAllowed, NotFound, ParseError

from .i18n import localize, localize_many, resolve_language
from .logging_setup import log_event, logger
from .problems import AppProblem, ValidationProblem, first_param, problem_response


def drf_exception_handler(exc: Exception, context: dict[str, Any]):
    request = context.get("request")
    if isinstance(exc, ValidationProblem):
        log_event(
            "info",
            "input_validation_failed",
            "failure",
            "Request validation failed",
            error_code="validation_failed",
            fields=",".join(violation.field for violation in exc.violations),
        )
        language = resolve_language(
            first_param(request, "lang") if request is not None else None,
            request.headers.get("accept-language") if request is not None else None,
        )
        messages = localize_many((violation.message_key for violation in exc.violations), language)
        errors = [
            {
                "field": violation.field,
                "messageKey": violation.message_key,
                "message": messages[violation.message_key],
            }
            for violation in exc.violations
        ]
        return problem_response(400, "Bad Request", "Request validation failed.", errors)

    if isinstance(exc, AppProblem):
        detail = exc.detail
        if exc.detail_key is not None:
            language = resolve_language(
                first_param(request, "lang") if request is not None else None,
                request.headers.get("accept-language") if request is not None else None,
            )
            detail = localize(exc.detail_key, language)
        return problem_response(exc.status, exc.title, detail)

    if isinstance(exc, ParseError):
        log_event(
            "info",
            "input_validation_failed",
            "failure",
            "Request body parsing failed",
            error_code="request_parse_failed",
        )
        return problem_response(400, "Bad Request", "Request validation failed.")

    if isinstance(exc, Http404 | NotFound):
        return problem_response(404, "Not Found")

    if isinstance(exc, MethodNotAllowed):
        return problem_response(405, "Method Not Allowed")

    if isinstance(exc, DatabaseError):
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
