from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx
import jwt
from django.db import IntegrityError, transaction
from jwt import PyJWKClient
from rest_framework.authentication import BaseAuthentication, get_authorization_header

from .config import config
from .i18n import localize, resolve_language
from .logging_setup import log_event
from .models import UserAccount
from .problems import ForbiddenProblem, UnauthorizedProblem, first_param
from .time import now_utc

APP_ROLES = {"moderator", "admin"}


@dataclass(frozen=True)
class Caller:
    username: str
    roles: list[str]
    name: str | None = None
    email: str | None = None
    is_authenticated: bool = True


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
    now = now_utc()
    try:
        with transaction.atomic():
            account, _created = UserAccount.objects.update_or_create(
                username=username,
                defaults={"last_seen": now},
                create_defaults={"first_seen": now, "last_seen": now, "status": "active"},
            )
            return account.status
    except IntegrityError:
        UserAccount.objects.filter(username=username).update(last_seen=now)
        return str(UserAccount.objects.only("status").get(username=username).status)


class StackverseJWTAuthentication(BaseAuthentication):
    def authenticate(self, request: Any) -> tuple[Caller, None] | None:
        raw = get_authorization_header(request).decode("iso-8859-1")
        if not raw:
            return None
        if not raw.startswith("Bearer "):
            raise UnauthorizedProblem("Missing or invalid bearer token.")
        try:
            caller = verify_bearer(raw.removeprefix("Bearer "))
        except Exception as exc:
            log_event(
                "info",
                "jwt_validation_failed",
                "failure",
                "Rejected a bearer token",
                error_code=exc.__class__.__name__.lower(),
            )
            raise UnauthorizedProblem("Missing or invalid bearer token.") from exc
        status = record_seen(caller.username)
        if status == "blocked":
            log_event(
                "warn",
                "blocked_user_rejected",
                "denied",
                "Refused a request from a blocked account",
                actor=caller.username,
            )
            language = resolve_language(first_param(request, "lang"), request.headers.get("accept-language"))
            raise ForbiddenProblem(localize("error.account.blocked", language))
        return caller, None

    def authenticate_header(self, request: Any) -> str:
        return "Bearer"


def require_caller(request: Any) -> Caller:
    caller = getattr(request, "user", None)
    if not isinstance(caller, Caller):
        raise UnauthorizedProblem()
    return caller


def require_role(request: Any, role: str) -> Caller:
    caller = require_caller(request)
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
