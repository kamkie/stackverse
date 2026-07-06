from __future__ import annotations

import hmac
import secrets
from urllib.parse import urlsplit

from starlette.requests import Request
from starlette.responses import Response

XSRF_COOKIE = "XSRF-TOKEN"
XSRF_HEADER = "x-xsrf-token"
SESSION_COOKIE = "stackverse_session"
CONTENT_SECURITY_POLICY = "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'"
STRICT_TRANSPORT_SECURITY = "max-age=31536000; includeSubDomains"

_STATE_CHANGING = {"POST", "PUT", "PATCH", "DELETE"}


def random_token(bytes_count: int = 32) -> str:
    return secrets.token_urlsafe(bytes_count)


def issue_csrf_cookie(request: Request, response: Response, secure: bool) -> None:
    if request.cookies.get(XSRF_COOKIE):
        return
    response.set_cookie(
        XSRF_COOKIE,
        random_token(16),
        httponly=False,
        secure=secure,
        samesite="lax",
        path="/",
    )


def has_valid_csrf(request: Request) -> bool:
    if request.method.upper() not in _STATE_CHANGING:
        return True
    cookie = request.cookies.get(XSRF_COOKIE)
    header = request.headers.get(XSRF_HEADER)
    return bool(cookie and header and hmac.compare_digest(cookie, header))


def is_same_origin_state_change(request: Request, expected_origin: str) -> bool:
    if not request.url.path.startswith("/api") or request.method.upper() not in _STATE_CHANGING:
        return True

    origin = request.headers.get("origin")
    if origin and _canonical_origin_or_none(origin) != expected_origin:
        return False

    fetch_site = request.headers.get("sec-fetch-site")
    return not fetch_site or fetch_site.lower() in {"same-origin", "none"}


def canonical_public_origin(public_url: str) -> str:
    return _canonical_origin(public_url)


def apply_security_headers(request: Request, response: Response, https_public_mode: bool) -> None:
    api_response = request.url.path.startswith("/api")
    response.headers["X-Content-Type-Options"] = "nosniff"
    if https_public_mode:
        response.headers["Strict-Transport-Security"] = STRICT_TRANSPORT_SECURITY
    if api_response:
        return

    response.headers["Referrer-Policy"] = "same-origin"
    response.headers["Content-Security-Policy"] = CONTENT_SECURITY_POLICY
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Cross-Origin-Opener-Policy"] = "same-origin"
    response.headers["Cross-Origin-Resource-Policy"] = "same-origin"


def _canonical_origin_or_none(value: str) -> str | None:
    if value.endswith("/"):
        return None
    try:
        origin = _canonical_origin(value)
    except ValueError:
        return None
    return origin if value == origin else None


def _canonical_origin(value: str) -> str:
    parts = urlsplit(value)
    if not parts.scheme or not parts.hostname or parts.path not in {"", "/"} or parts.query or parts.fragment:
        raise ValueError("origin must be a bare scheme/host/port")

    scheme = parts.scheme.lower()
    host = parts.hostname.lower()
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    default_port = (scheme == "http" and parts.port in {None, 80}) or (scheme == "https" and parts.port in {None, 443})
    return f"{scheme}://{host}{'' if default_port else f':{parts.port}'}"
