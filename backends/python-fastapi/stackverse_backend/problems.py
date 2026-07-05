from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from fastapi import Request
from fastapi.responses import JSONResponse


class AppProblem(Exception):
    def __init__(self, status: int, title: str, detail: str | None = None, detail_key: str | None = None):
        super().__init__(detail or title)
        self.status = status
        self.title = title
        self.detail = detail
        self.detail_key = detail_key


class NotFoundProblem(AppProblem):
    def __init__(self) -> None:
        super().__init__(404, "Not Found")


class UnauthorizedProblem(AppProblem):
    def __init__(self, detail: str = "Authentication is required.") -> None:
        super().__init__(401, "Unauthorized", detail)


class ForbiddenProblem(AppProblem):
    def __init__(self, detail: str, detail_key: str | None = None) -> None:
        super().__init__(403, "Forbidden", detail, detail_key)


class ConflictProblem(AppProblem):
    def __init__(self, detail: str, detail_key: str | None = None) -> None:
        super().__init__(409, "Conflict", detail, detail_key)


class BadRequestProblem(AppProblem):
    def __init__(self, detail: str) -> None:
        super().__init__(400, "Bad Request", detail)


@dataclass(frozen=True)
class FieldViolation:
    field: str
    message_key: str


class ValidationProblem(Exception):
    def __init__(self, violations: list[FieldViolation]):
        super().__init__("Validation failed")
        self.violations = violations


class Validator:
    def __init__(self) -> None:
        self.violations: list[FieldViolation] = []

    def reject(self, field: str, message_key: str) -> None:
        self.violations.append(FieldViolation(field, message_key))

    def check(self, condition: bool, field: str, message_key: str) -> None:
        if not condition:
            self.reject(field, message_key)

    def throw_if_invalid(self) -> None:
        if self.violations:
            raise ValidationProblem(self.violations)


def problem_response(
    status: int,
    title: str,
    detail: str | None = None,
    errors: list[dict[str, Any]] | None = None,
) -> JSONResponse:
    payload: dict[str, Any] = {"type": "about:blank", "title": title, "status": status}
    if detail is not None:
        payload["detail"] = detail
    if errors is not None:
        payload["errors"] = errors
    return JSONResponse(status_code=status, content=payload, media_type="application/problem+json")


def single_param(request: Request, name: str) -> str | None:
    values = request.query_params.getlist(name)
    if not values:
        return None
    if len(values) > 1:
        raise BadRequestProblem(f"{name} must not be repeated")
    return values[0]


def first_param(request: Request, name: str) -> str | None:
    values = request.query_params.getlist(name)
    return values[0] if values else None


def multi_param(request: Request, name: str) -> list[str]:
    return [str(value) for value in request.query_params.getlist(name)]


def require_valid_paging(request: Request) -> tuple[int, int]:
    page = _int_param(single_param(request, "page"), 0, "page")
    size = _int_param(single_param(request, "size"), 20, "size")
    if page < 0:
        raise BadRequestProblem("page must not be negative")
    if size < 1 or size > 100:
        raise BadRequestProblem("size must be between 1 and 100")
    return page, size


def _int_param(value: str | None, fallback: int, name: str) -> int:
    if value is None or value == "":
        return fallback
    if not re.fullmatch(r"-?\d+", value):
        raise BadRequestProblem(f"{name} must be an integer")
    return int(value)


def require_max_length(value: str | None, max_length: int, name: str) -> None:
    if value is not None and len(value) > max_length:
        raise BadRequestProblem(f"{name} must be at most {max_length} characters")


def escape_like(value: str) -> str:
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


UUID_PATTERN = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.I)


def parse_uuid(value: str) -> str:
    if not UUID_PATTERN.fullmatch(value):
        raise NotFoundProblem()
    return value.lower()


def omit_none(payload: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in payload.items() if value is not None}
