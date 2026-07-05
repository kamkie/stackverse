from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Annotated, Any

import httpx
import jwt
from fastapi import Depends, Request
from jwt import PyJWKClient
from starlette.concurrency import run_in_threadpool

from .config import config
from .db import query
from .i18n import localize, resolve_language
from .logging_setup import log_event
from .problems import ForbiddenProblem, UnauthorizedProblem, first_param
from .time import now_utc

APP_ROLES = {"moderator", "admin"}


@dataclass(frozen=True)
class Caller:
    username: str
    roles: list[str]
    name: str | None = None
    email: str | None = None


_jwk_client: PyJWKClient | None = None


def _jwks_uri() -> str:
    if config.oidc_jwks_uri:
        return config.oidc_jwks_uri
    discovery_url = f"{config.oidc_issuer_uri}/.well-known/openid-configuration"
    try:
        response = httpx.get(discovery_url, timeout=5)
        response.raise_for_status()
        return str(response.json()["jwks_uri"])
    except Exception as exc:
        log_event(
            "error",
            "dependency_call_failed",
            "failure",
            "OIDC discovery failed",
            dependency="keycloak",
            error_code="oidc_discovery_failed",
        )
        raise exc


def _client() -> PyJWKClient:
    global _jwk_client
    if _jwk_client is None:
        _jwk_client = PyJWKClient(_jwks_uri())
    return _jwk_client


def verify_bearer(token: str) -> Caller:
    signing_key = _client().get_signing_key_from_jwt(token)
    payload: dict[str, Any] = jwt.decode(
        token,
        signing_key.key,
        algorithms=["RS256"],
        audience=config.oidc_audience,
        issuer=config.oidc_issuer_uri,
    )
    username = payload.get("preferred_username")
    if not isinstance(username, str) or not username:
        raise jwt.InvalidTokenError("missing preferred_username")
    raw_roles = payload.get("realm_access", {}).get("roles", [])
    roles = [role for role in raw_roles if isinstance(role, str)]
    return Caller(
        username=username,
        roles=roles,
        name=payload.get("name") if isinstance(payload.get("name"), str) else None,
        email=payload.get("email") if isinstance(payload.get("email"), str) else None,
    )


def record_seen(username: str) -> str:
    rows = query(
        """
        insert into user_accounts (username, first_seen, last_seen, status)
        values (%s, %s, %s, 'active')
        on conflict (username) do update set last_seen = excluded.last_seen
        returning status
        """,
        (username, now_utc(), now_utc()),
    )
    return str(rows[0]["status"])


async def authenticate_request(request: Request) -> Caller | None:
    header = request.headers.get("authorization")
    if not header or not header.startswith("Bearer "):
        return None
    try:
        caller = await run_in_threadpool(verify_bearer, header.removeprefix("Bearer "))
    except Exception as exc:
        log_event(
            "info",
            "jwt_validation_failed",
            "failure",
            "Rejected a bearer token",
            error_code=exc.__class__.__name__.lower(),
        )
        raise UnauthorizedProblem("Missing or invalid bearer token.") from exc
    status = await run_in_threadpool(record_seen, caller.username)
    if status == "blocked":
        log_event(
            "warn",
            "blocked_user_rejected",
            "denied",
            "Refused a request from a blocked account",
            actor=caller.username,
        )
        language = await run_in_threadpool(
            resolve_language,
            first_param(request, "lang"),
            request.headers.get("accept-language"),
        )
        detail = await run_in_threadpool(localize, "error.account.blocked", language)
        raise ForbiddenProblem(detail)
    return caller


def require_caller(caller: Annotated[Caller | None, Depends(authenticate_request)]) -> Caller:
    if caller is None:
        raise UnauthorizedProblem()
    return caller


def require_role(role: str) -> Callable[[Caller], Caller]:
    def role_dependency(caller: Annotated[Caller, Depends(require_caller)]) -> Caller:
        if role not in caller.roles:
            log_event(
                "info",
                "authz_denied",
                "denied",
                "Denied a request lacking the required role",
                actor=caller.username,
            )
            raise ForbiddenProblem("You do not have the role required for this operation.")
        return caller

    return role_dependency


OptionalCaller = Annotated[Caller | None, Depends(authenticate_request)]
CurrentCaller = Annotated[Caller, Depends(require_caller)]
ModeratorCaller = Annotated[Caller, Depends(require_role("moderator"))]
AdminCaller = Annotated[Caller, Depends(require_role("admin"))]


def me_response(caller: Caller) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "username": caller.username,
        "roles": sorted(role for role in caller.roles if role in APP_ROLES),
    }
    if caller.name is not None:
        payload["name"] = caller.name
    if caller.email is not None:
        payload["email"] = caller.email
    return payload
