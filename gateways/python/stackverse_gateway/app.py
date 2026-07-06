from __future__ import annotations

import mimetypes
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import FileResponse, JSONResponse, RedirectResponse, Response
from starlette.routing import Route

from .config import GatewayConfig, load_config
from .logging import configure_logging, log_event, sanitize_log_value
from .oidc import IdpUnavailableError, OidcClient, TokenSet, username_from_id_token
from .otel import configure_otel
from .problems import problem_response
from .proxy import proxy_request
from .security import (
    SESSION_COOKIE,
    XSRF_HEADER,
    apply_security_headers,
    canonical_public_origin,
    has_valid_csrf,
    is_same_origin_state_change,
    issue_csrf_cookie,
    random_token,
)
from .sessions import GatewaySession, LoginState, RedisSessionStore, SessionStore, now_ms

SESSION_TTL_SECONDS = 8 * 60 * 60
LOGIN_STATE_TTL_SECONDS = 10 * 60
REFRESH_SKEW_MS = 30_000
ALL_METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"]


def build_app(
    *,
    config: GatewayConfig | None = None,
    session_store: SessionStore | None = None,
    oidc_client: OidcClient | None = None,
    http_client: httpx.AsyncClient | None = None,
) -> Starlette:
    gateway = config or load_config()
    configure_logging(gateway)
    store = session_store or RedisSessionStore(gateway.redis_url)
    client = http_client or httpx.AsyncClient(timeout=20.0, follow_redirects=False)
    oidc = oidc_client or OidcClient(gateway, client)
    expected_origin = canonical_public_origin(gateway.public_url)
    owns_store = session_store is None
    owns_client = http_client is None

    async def login(_request: Request) -> Response:
        state = random_token(24)
        code_verifier = random_token(32)
        nonce = random_token(24)
        await store.set_login_state(
            state, LoginState(code_verifier=code_verifier, nonce=nonce, created_at=now_ms()), LOGIN_STATE_TTL_SECONDS
        )
        try:
            return RedirectResponse(await oidc.authorization_url(state, code_verifier, nonce), status_code=302)
        except Exception as exc:
            log_event(
                "error",
                "dependency_call_failed",
                "failure",
                "OIDC discovery failed during login",
                dependency="keycloak",
                error_code=type(exc).__name__,
            )
            return problem_response(
                503, "Service Unavailable", "Authentication is temporarily unavailable; please retry."
            )

    async def callback(request: Request) -> Response:
        if (
            request.query_params.get("error")
            or not request.query_params.get("code")
            or not request.query_params.get("state")
        ):
            log_event(
                "info",
                "oidc_callback_completed",
                "failure",
                "Authorization code flow failed",
                error_code=sanitize_log_value(request.query_params.get("error") or "invalid_callback"),
            )
            return RedirectResponse("/", status_code=302)

        login_state = await store.consume_login_state(request.query_params["state"])
        if login_state is None:
            log_event(
                "info",
                "oidc_callback_completed",
                "failure",
                "Authorization code flow failed",
                error_code="invalid_state",
            )
            return RedirectResponse("/", status_code=302)

        try:
            tokens = await oidc.exchange_code(request.query_params["code"], login_state.code_verifier)
            if tokens.id_token is None:
                raise RuntimeError("missing_id_token")
            payload = await oidc.verify_id_token(tokens.id_token, login_state.nonce)
            username = username_from_id_token(payload)
            session_id = await store.create_session(_session_from_tokens(username, tokens), SESSION_TTL_SECONDS)
            response = RedirectResponse("/", status_code=302)
            _set_session_cookie(response, session_id, gateway.cookies_secure)
            log_event("info", "oidc_callback_completed", "success", "Authorization code flow completed", actor=username)
            log_event("info", "session_created", "success", "Session stored in Redis, cookie issued", actor=username)
            return response
        except Exception as exc:
            log_event(
                "info",
                "oidc_callback_completed",
                "failure",
                "Authorization code flow failed",
                error_code=sanitize_log_value(str(exc)) or type(exc).__name__,
            )
            return RedirectResponse("/", status_code=302)

    async def auth_session(request: Request) -> Response:
        loaded = await _load_session(request, store)
        if loaded is None:
            return JSONResponse({"authenticated": False})
        return JSONResponse({"authenticated": True, "username": loaded[1].username})

    async def logout(request: Request) -> Response:
        loaded = await _load_session(request, store)
        response = Response(status_code=204)
        if loaded is None:
            _clear_session_cookie(response, gateway.cookies_secure)
            return response

        session_id, session = loaded
        await store.destroy_session(session_id)
        _clear_session_cookie(response, gateway.cookies_secure)
        log_event(
            "info",
            "session_destroyed",
            "success",
            "Session destroyed by user logout",
            reason="logout",
            actor=session.username,
        )
        if session.refresh_token:
            await oidc.logout(session.refresh_token)
        return response

    async def api(request: Request) -> Response:
        if not is_same_origin_state_change(request, expected_origin):
            log_event(
                "info",
                "csrf_validation_failed",
                "denied",
                "Rejected a cross-origin state-changing /api request",
                method=sanitize_log_value(request.method),
                path=sanitize_log_value(request.url.path),
            )
            return problem_response(403, "Forbidden", "Cross-origin state-changing requests are not supported.")
        if not has_valid_csrf(request):
            log_event(
                "info",
                "csrf_validation_failed",
                "denied",
                "Rejected a state-changing /api request without a matching CSRF header",
                method=sanitize_log_value(request.method),
                path=sanitize_log_value(request.url.path),
            )
            return problem_response(403, "Forbidden", f"Missing or mismatched {XSRF_HEADER.upper()} header.")

        loaded = await _load_session(request, store)
        access_token: str | None = None
        clear_dead_session = False
        if loaded is not None:
            session_id, session = loaded
            try:
                access_token = await _access_token_for_session(session_id, session, store, oidc)
            except IdpUnavailableError:
                return problem_response(
                    503, "Service Unavailable", "Authentication is temporarily unavailable; please retry."
                )
            if access_token is None:
                await store.destroy_session(session_id)
                clear_dead_session = True
                log_event(
                    "info",
                    "session_destroyed",
                    "success",
                    "Session destroyed after a failed token refresh; request degraded to anonymous",
                    reason="token_refresh_failed",
                    actor=session.username,
                )

        response = await proxy_request(request, gateway.backend_url, "backend", client, access_token)
        if clear_dead_session:
            _clear_session_cookie(response, gateway.cookies_secure)
        return response

    async def frontend(request: Request) -> Response:
        if gateway.frontend_url:
            return await proxy_request(request, gateway.frontend_url, "frontend", client)
        return await _static_spa(request, gateway.spa_root)

    @asynccontextmanager
    async def lifespan(app: Starlette) -> AsyncIterator[None]:
        shutdown_otel = configure_otel(app, gateway)
        log_event(
            "info",
            "application_start",
            "success",
            "Gateway listening",
            port=gateway.port,
            backend_url=gateway.backend_url,
            public_url=gateway.public_url,
            frontend_mode="proxy" if gateway.frontend_url else "static",
            cookies_secure=gateway.cookies_secure,
        )
        try:
            yield
        finally:
            if owns_client:
                await client.aclose()
            if owns_store:
                await store.close()
            shutdown_otel()
            log_event("info", "application_stop", "success", "Gateway stopped")

    app = Starlette(
        routes=[
            Route("/auth/login", login, methods=["GET"]),
            Route("/auth/callback", callback, methods=["GET"]),
            Route("/auth/session", auth_session, methods=["GET"]),
            Route("/auth/logout", logout, methods=["POST"]),
            Route("/api", api, methods=ALL_METHODS),
            Route("/api/{path:path}", api, methods=ALL_METHODS),
            Route("/{path:path}", frontend, methods=ALL_METHODS),
        ],
        lifespan=lifespan,
        middleware=[Middleware(EdgeSecurityMiddleware, secure=gateway.cookies_secure)],
    )

    return app


class EdgeSecurityMiddleware(BaseHTTPMiddleware):
    def __init__(self, app: object, secure: bool) -> None:
        super().__init__(app)
        self._secure = secure

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        response = await call_next(request)
        issue_csrf_cookie(request, response, self._secure)
        apply_security_headers(request, response, self._secure)
        return response


def _session_from_tokens(username: str, tokens: TokenSet) -> GatewaySession:
    now = now_ms()
    return GatewaySession(
        username=username,
        access_token=tokens.access_token,
        refresh_token=tokens.refresh_token,
        id_token=tokens.id_token,
        expires_at=now + tokens.expires_in * 1000,
        created_at=now,
        updated_at=now,
    )


async def _access_token_for_session(
    session_id: str,
    session: GatewaySession,
    store: SessionStore,
    oidc: OidcClient,
) -> str | None:
    if session.access_token and session.expires_at - REFRESH_SKEW_MS > now_ms():
        return session.access_token
    if not session.refresh_token:
        return None

    refreshed = await oidc.refresh(session.refresh_token)
    if refreshed is None:
        return None

    session.access_token = refreshed.access_token
    session.expires_at = now_ms() + refreshed.expires_in * 1000
    session.updated_at = now_ms()
    if refreshed.refresh_token:
        session.refresh_token = refreshed.refresh_token
    if refreshed.id_token:
        session.id_token = refreshed.id_token
    await store.save_session(session_id, session, SESSION_TTL_SECONDS)
    return session.access_token


async def _load_session(request: Request, store: SessionStore) -> tuple[str, GatewaySession] | None:
    session_id = request.cookies.get(SESSION_COOKIE)
    if not session_id:
        return None
    session = await store.get_session(session_id)
    return (session_id, session) if session else None


def _set_session_cookie(response: Response, session_id: str, secure: bool) -> None:
    response.set_cookie(
        SESSION_COOKIE,
        session_id,
        max_age=SESSION_TTL_SECONDS,
        httponly=True,
        secure=secure,
        samesite="lax",
        path="/",
    )


def _clear_session_cookie(response: Response, secure: bool) -> None:
    response.delete_cookie(SESSION_COOKIE, path="/", secure=secure, httponly=True, samesite="lax")


async def _static_spa(request: Request, root: Path) -> Response:
    if request.method not in {"GET", "HEAD"}:
        return problem_response(404, "Not Found", "No route matched the request.")

    resolved_root = root.resolve()
    relative = request.url.path.lstrip("/") or "index.html"
    candidate = (resolved_root / relative).resolve()
    try:
        candidate.relative_to(resolved_root)
    except ValueError:
        candidate = resolved_root / "index.html"

    if not candidate.is_file():
        candidate = resolved_root / "index.html"

    media_type = mimetypes.guess_type(candidate.name)[0] or "application/octet-stream"
    return FileResponse(candidate, media_type=media_type)
