from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import asdict, dataclass
from urllib.parse import parse_qs, urlsplit

import anyio
import httpx
import pytest
from opentelemetry import trace
from opentelemetry.trace import NonRecordingSpan, SpanContext, TraceFlags
from starlette.testclient import TestClient

from stackverse_gateway.app import SESSION_TTL_SECONDS, build_app
from stackverse_gateway.config import GatewayConfig, load_config
from stackverse_gateway.oidc import TokenSet
from stackverse_gateway.sessions import GatewaySession, LoginState, MemorySessionStore, SessionStore, now_ms

BACKEND_ORIGIN = "http://backend.test"
FRONTEND_ORIGIN = "http://frontend.test"
IDP_ORIGIN = "http://idp.test/realms/stackverse"


@dataclass
class Call:
    method: str
    url: str
    headers: httpx.Headers
    body: bytes


Handler = Callable[[str, str, httpx.Headers, bytes], httpx.Response | Exception]


class StubHttpClient:
    def __init__(self, handler: Handler) -> None:
        self.handler = handler
        self.calls: list[Call] = []

    def build_request(self, method: str, url: str, headers=None, content: bytes | None = None) -> httpx.Request:  # type: ignore[no-untyped-def]
        return httpx.Request(method, url, headers=headers, content=content)

    async def send(self, request: httpx.Request, stream: bool = False) -> httpx.Response:
        del stream
        body = request.content
        self.calls.append(Call(request.method, str(request.url), request.headers, body))
        result = self.handler(request.method, str(request.url), request.headers, body)
        if isinstance(result, Exception):
            raise result
        result.request = request
        return result

    async def get(self, url: str) -> httpx.Response:
        request = httpx.Request("GET", url)
        self.calls.append(Call("GET", url, request.headers, b""))
        result = self.handler("GET", url, request.headers, b"")
        if isinstance(result, Exception):
            raise result
        result.request = request
        return result

    async def post(self, url: str, data=None, headers=None) -> httpx.Response:  # type: ignore[no-untyped-def]
        request = httpx.Request("POST", url, headers=headers, data=data)
        self.calls.append(Call("POST", url, request.headers, request.content))
        result = self.handler("POST", url, request.headers, request.content)
        if isinstance(result, Exception):
            raise result
        result.request = request
        return result

    async def aclose(self) -> None:
        return None


class CallbackOidc:
    def __init__(self, *, tokens: TokenSet | None = None, verification_error: Exception | None = None) -> None:
        self.tokens = tokens or TokenSet("access", 120, refresh_token="refresh")
        self.verification_error = verification_error
        self.exchange_calls = 0
        self.verify_calls = 0

    async def authorization_url(self, state: str, _code_verifier: str, _nonce: str) -> str:
        return f"{IDP_ORIGIN}/auth?state={state}"

    async def exchange_code(self, _code: str, _code_verifier: str) -> TokenSet:
        self.exchange_calls += 1
        return self.tokens

    async def verify_id_token(self, _id_token: str, _nonce: str) -> dict[str, str]:
        self.verify_calls += 1
        if self.verification_error:
            raise self.verification_error
        return {"preferred_username": "alice"}

    async def logout(self, _refresh_token: str) -> None:
        return None

    async def refresh(self, _refresh_token: str) -> TokenSet | None:
        raise AssertionError("refresh should not be called")


class FailingLoginOidc(CallbackOidc):
    async def authorization_url(self, _state: str, _code_verifier: str, _nonce: str) -> str:
        raise httpx.ConnectError("discovery unavailable")


class RefreshingOidc(CallbackOidc):
    def __init__(self, refreshed: TokenSet) -> None:
        super().__init__()
        self.refreshed = refreshed
        self.refresh_calls = 0

    async def refresh(self, refresh_token: str) -> TokenSet | None:
        self.refresh_calls += 1
        assert refresh_token == "old-refresh"
        return self.refreshed


class CopyingSessionStore(SessionStore):
    def __init__(self) -> None:
        self.sessions: dict[str, GatewaySession] = {}
        self.states: dict[str, LoginState] = {}
        self.save_calls: list[tuple[str, GatewaySession, int]] = []
        self.destroyed: list[str] = []

    async def create_session(self, session: GatewaySession, ttl_seconds: int) -> str:
        await self.save_session("created-session", session, ttl_seconds)
        return "created-session"

    async def get_session(self, session_id: str) -> GatewaySession | None:
        session = self.sessions.get(session_id)
        return GatewaySession(**asdict(session)) if session else None

    async def save_session(self, session_id: str, session: GatewaySession, ttl_seconds: int) -> None:
        saved = GatewaySession(**asdict(session))
        self.sessions[session_id] = saved
        self.save_calls.append((session_id, GatewaySession(**asdict(saved)), ttl_seconds))

    async def destroy_session(self, session_id: str) -> None:
        self.destroyed.append(session_id)
        self.sessions.pop(session_id, None)

    async def set_login_state(self, state: str, value: LoginState, ttl_seconds: int) -> None:
        del ttl_seconds
        self.states[state] = LoginState(**asdict(value))

    async def consume_login_state(self, state: str) -> LoginState | None:
        return self.states.pop(state, None)

    async def close(self) -> None:
        return None


class ChunkStream(httpx.AsyncByteStream):
    def __init__(self, chunks: list[bytes]) -> None:
        self.chunks = chunks
        self.closed = False

    async def __aiter__(self):  # type: ignore[no-untyped-def]
        for chunk in self.chunks:
            yield chunk

    async def aclose(self) -> None:
        self.closed = True


def make_config(overrides: dict[str, str] | None = None) -> GatewayConfig:
    return load_config(
        {
            "PORT": "0",
            "BACKEND_URL": BACKEND_ORIGIN,
            "FRONTEND_URL": FRONTEND_ORIGIN,
            "REDIS_URL": "redis://redis.test:6379",
            "OIDC_ISSUER_URI": IDP_ORIGIN,
            "OIDC_CLIENT_ID": "stackverse-gateway",
            "OIDC_CLIENT_SECRET": "client-secret-value",
            "PUBLIC_URL": "http://localhost:8000",
            "OTEL_SDK_DISABLED": "true",
            **(overrides or {}),
        }
    )


def run[T](awaitable: Awaitable[T]) -> T:
    async def runner() -> T:
        return await awaitable

    return anyio.run(runner)


def fresh_session(**overrides: object) -> GatewaySession:
    now = now_ms()
    values: dict[str, object] = {
        "username": "alice",
        "access_token": "access-value",
        "refresh_token": "refresh-value",
        "expires_at": now + 60_000,
        "created_at": now,
        "updated_at": now,
    }
    values.update(overrides)
    return GatewaySession(**values)  # type: ignore[arg-type]


def backend_ok(_method: str, url: str, _headers: httpx.Headers, _body: bytes) -> httpx.Response:
    if url.startswith(BACKEND_ORIGIN):
        return httpx.Response(200, json={"ok": True})
    if url.startswith(FRONTEND_ORIGIN):
        return httpx.Response(200, text="frontend")
    return httpx.Response(404)


def test_login_dependency_failure_returns_problem_without_exposing_secrets(capsys: pytest.CaptureFixture[str]) -> None:
    store = MemorySessionStore()
    with TestClient(
        build_app(
            config=make_config(),
            session_store=store,
            oidc_client=FailingLoginOidc(),  # type: ignore[arg-type]
            http_client=StubHttpClient(backend_ok),  # type: ignore[arg-type]
        )
    ) as client:
        response = client.get("/auth/login")

    output = capsys.readouterr().out
    assert response.status_code == 503
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["detail"] == "Authentication is temporarily unavailable; please retry."
    assert '"event":"dependency_call_failed"' in output
    assert "client-secret-value" not in output


def test_callback_with_unknown_state_redirects_without_exchange_or_session() -> None:
    oidc = CallbackOidc(tokens=TokenSet("access", 120, id_token="id-token"))
    with TestClient(
        build_app(
            config=make_config(),
            session_store=MemorySessionStore(),
            oidc_client=oidc,  # type: ignore[arg-type]
            http_client=StubHttpClient(backend_ok),  # type: ignore[arg-type]
        )
    ) as client:
        response = client.get("/auth/callback?code=authorization-code&state=unknown", follow_redirects=False)

    assert response.status_code == 302
    assert response.headers["location"] == "/"
    assert oidc.exchange_calls == 0
    assert "stackverse_session=" not in response.headers.get("set-cookie", "")


def test_successful_callback_issues_only_an_opaque_contract_session_cookie() -> None:
    oidc = CallbackOidc(tokens=TokenSet("server-access", 120, "server-refresh", "server-id"))
    with TestClient(
        build_app(
            config=make_config({"PUBLIC_URL": "https://stackverse.example"}),
            session_store=MemorySessionStore(),
            oidc_client=oidc,  # type: ignore[arg-type]
            http_client=StubHttpClient(backend_ok),  # type: ignore[arg-type]
        )
    ) as client:
        login = client.get("/auth/login", follow_redirects=False)
        state = parse_qs(urlsplit(login.headers["location"]).query)["state"][0]
        callback = client.get(f"/auth/callback?code=authorization-code&state={state}", follow_redirects=False)

    cookie = next(
        value for value in callback.headers.get_list("set-cookie") if value.lower().startswith("stackverse_session=")
    ).lower()
    assert "server-access" not in cookie
    assert "server-refresh" not in cookie
    assert "server-id" not in cookie
    assert "max-age=28800" in cookie
    assert "httponly" in cookie
    assert "secure" in cookie
    assert "samesite=lax" in cookie
    assert "path=/" in cookie


@pytest.mark.parametrize("failure", ["missing-id-token", "invalid-id-token"])
def test_callback_failure_consumes_state_and_never_creates_a_session(failure: str) -> None:
    store = MemorySessionStore()
    if failure == "missing-id-token":
        oidc = CallbackOidc(tokens=TokenSet("access", 120))
    else:
        oidc = CallbackOidc(
            tokens=TokenSet("access", 120, id_token="invalid"),
            verification_error=ValueError("invalid signature"),
        )

    with TestClient(
        build_app(
            config=make_config(),
            session_store=store,
            oidc_client=oidc,  # type: ignore[arg-type]
            http_client=StubHttpClient(backend_ok),  # type: ignore[arg-type]
        )
    ) as client:
        login = client.get("/auth/login", follow_redirects=False)
        state = parse_qs(urlsplit(login.headers["location"]).query)["state"][0]
        first = client.get(f"/auth/callback?code=authorization-code&state={state}", follow_redirects=False)
        replay = client.get(f"/auth/callback?code=authorization-code&state={state}", follow_redirects=False)
        session = client.get("/auth/session")

    assert first.status_code == 302
    assert replay.status_code == 302
    assert "stackverse_session=" not in first.headers.get("set-cookie", "")
    assert oidc.exchange_calls == 1
    assert session.json() == {"authenticated": False}


def test_anonymous_logout_clears_stale_cookie_with_contract_attributes() -> None:
    with TestClient(
        build_app(
            config=make_config({"PUBLIC_URL": "https://stackverse.example"}),
            session_store=MemorySessionStore(),
            oidc_client=CallbackOidc(),  # type: ignore[arg-type]
            http_client=StubHttpClient(backend_ok),  # type: ignore[arg-type]
        )
    ) as client:
        response = client.post("/auth/logout", headers={"cookie": "stackverse_session=stale"})

    cookie = response.headers["set-cookie"].lower()
    assert response.status_code == 204
    assert "stackverse_session=" in cookie
    assert "max-age=0" in cookie
    assert "httponly" in cookie
    assert "secure" in cookie
    assert "samesite=lax" in cookie
    assert "path=/" in cookie


def test_expired_session_without_refresh_token_is_destroyed_cleared_and_relayed_anonymously() -> None:
    store = CopyingSessionStore()
    run(
        store.save_session(
            "session-id",
            fresh_session(access_token="expired", refresh_token=None, expires_at=now_ms() - 60_000),
            SESSION_TTL_SECONDS,
        )
    )
    store.save_calls.clear()
    http = StubHttpClient(backend_ok)

    with TestClient(
        build_app(
            config=make_config(),
            session_store=store,
            oidc_client=CallbackOidc(),  # type: ignore[arg-type]
            http_client=http,  # type: ignore[arg-type]
        )
    ) as client:
        response = client.get("/api/v1/messages", headers={"cookie": "stackverse_session=session-id"})

    backend = [call for call in http.calls if call.url.startswith(BACKEND_ORIGIN)][-1]
    assert response.status_code == 200
    assert "session-id" in store.destroyed
    assert "authorization" not in backend.headers
    assert "max-age=0" in response.headers["set-cookie"].lower()


def test_successful_refresh_is_persisted_with_full_session_ttl_before_token_relay() -> None:
    store = CopyingSessionStore()
    run(
        store.save_session(
            "session-id",
            fresh_session(
                access_token="old-access",
                refresh_token="old-refresh",
                id_token="old-id",
                expires_at=now_ms() - 60_000,
            ),
            SESSION_TTL_SECONDS,
        )
    )
    store.save_calls.clear()
    oidc = RefreshingOidc(TokenSet("new-access", 300, refresh_token="new-refresh", id_token="new-id"))
    http = StubHttpClient(backend_ok)

    with TestClient(
        build_app(
            config=make_config(),
            session_store=store,
            oidc_client=oidc,  # type: ignore[arg-type]
            http_client=http,  # type: ignore[arg-type]
        )
    ) as client:
        response = client.get("/api/v1/bookmarks", headers={"cookie": "stackverse_session=session-id"})

    backend = [call for call in http.calls if call.url.startswith(BACKEND_ORIGIN)][-1]
    saved_id, saved, ttl = store.save_calls[-1]
    assert response.status_code == 200
    assert oidc.refresh_calls == 1
    assert saved_id == "session-id"
    assert ttl == SESSION_TTL_SECONDS
    assert saved.access_token == "new-access"
    assert saved.refresh_token == "new-refresh"
    assert saved.id_token == "new-id"
    assert backend.headers["authorization"] == "Bearer new-access"


@pytest.mark.parametrize("status", [400, 401, 403, 404, 409])
def test_backend_problem_responses_pass_through_without_gateway_rewrite(status: int) -> None:
    body = b'{"type":"about:blank","title":"Upstream decision","status":' + str(status).encode() + b"}"

    def handler(_method: str, url: str, _headers: httpx.Headers, _request_body: bytes) -> httpx.Response:
        if url.startswith(BACKEND_ORIGIN):
            return httpx.Response(
                status,
                content=body,
                headers={
                    "content-type": "application/problem+json",
                    "cache-control": "no-cache",
                    "content-language": "pl",
                    "connection": "keep-alive",
                },
            )
        return httpx.Response(200, text="frontend")

    with TestClient(
        build_app(
            config=make_config(),
            session_store=MemorySessionStore(),
            oidc_client=CallbackOidc(),  # type: ignore[arg-type]
            http_client=StubHttpClient(handler),  # type: ignore[arg-type]
        )
    ) as client:
        response = client.get("/api/v1/admin/reports")

    assert response.status_code == status
    assert response.content == body
    assert response.headers["content-type"] == "application/problem+json"
    assert response.headers["cache-control"] == "no-cache"
    assert response.headers["content-language"] == "pl"
    assert "connection" not in response.headers


@pytest.mark.parametrize(("method", "status"), [("GET", 304), ("HEAD", 200)])
def test_bodyless_upstream_responses_preserve_status_and_cache_headers(method: str, status: int) -> None:
    stream = ChunkStream([b"must-not-reach-browser"])

    def handler(_method: str, url: str, _headers: httpx.Headers, _body: bytes) -> httpx.Response:
        if url.startswith(BACKEND_ORIGIN):
            return httpx.Response(status, headers={"etag": '"bundle-v1"', "cache-control": "no-cache"}, stream=stream)
        return httpx.Response(200, text="frontend")

    with TestClient(
        build_app(
            config=make_config(),
            session_store=MemorySessionStore(),
            oidc_client=CallbackOidc(),  # type: ignore[arg-type]
            http_client=StubHttpClient(handler),  # type: ignore[arg-type]
        )
    ) as client:
        response = client.request(method, "/api/v1/messages/bundle")

    assert response.status_code == status
    assert response.content == b""
    assert response.headers["etag"] == '"bundle-v1"'
    assert response.headers["cache-control"] == "no-cache"
    assert stream.closed is True


def test_streams_upstream_body_and_closes_it_after_response() -> None:
    stream = ChunkStream([b"chunk-one", b"-chunk-two"])

    def handler(_method: str, url: str, _headers: httpx.Headers, _body: bytes) -> httpx.Response:
        if url.startswith(BACKEND_ORIGIN):
            return httpx.Response(200, headers={"content-type": "application/octet-stream"}, stream=stream)
        return httpx.Response(200, text="frontend")

    with TestClient(
        build_app(
            config=make_config(),
            session_store=MemorySessionStore(),
            oidc_client=CallbackOidc(),  # type: ignore[arg-type]
            http_client=StubHttpClient(handler),  # type: ignore[arg-type]
        )
    ) as client:
        response = client.get("/api/v1/export")

    assert response.content == b"chunk-one-chunk-two"
    assert stream.closed is True


def test_backend_transport_failure_returns_gateway_problem_and_safe_dependency_log(
    capsys: pytest.CaptureFixture[str],
) -> None:
    def handler(_method: str, url: str, _headers: httpx.Headers, _body: bytes) -> Exception | httpx.Response:
        if url.startswith(BACKEND_ORIGIN):
            return httpx.ConnectError("backend connection refused", request=httpx.Request("GET", url))
        return httpx.Response(200, text="frontend")

    with TestClient(
        build_app(
            config=make_config(),
            session_store=MemorySessionStore(),
            oidc_client=CallbackOidc(),  # type: ignore[arg-type]
            http_client=StubHttpClient(handler),  # type: ignore[arg-type]
        )
    ) as client:
        response = client.get(
            "/api/v1/bookmarks",
            headers={"cookie": "stackverse_session=opaque-secret", "authorization": "Bearer forged-secret"},
        )

    output = capsys.readouterr().out
    assert response.status_code == 502
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["title"] == "Bad Gateway"
    assert '"dependency":"backend"' in output
    assert "opaque-secret" not in output
    assert "forged-secret" not in output


def test_cross_origin_preflight_is_not_answered_with_cors_headers() -> None:
    http = StubHttpClient(backend_ok)
    with TestClient(
        build_app(
            config=make_config(),
            session_store=MemorySessionStore(),
            oidc_client=CallbackOidc(),  # type: ignore[arg-type]
            http_client=http,  # type: ignore[arg-type]
        )
    ) as client:
        response = client.options(
            "/api/v1/bookmarks",
            headers={
                "origin": "https://evil.example",
                "access-control-request-method": "POST",
                "access-control-request-headers": "x-xsrf-token",
            },
        )

    backend = [call for call in http.calls if call.url.startswith(BACKEND_ORIGIN)][-1]
    assert response.status_code == 200
    assert backend.method == "OPTIONS"
    assert "access-control-allow-origin" not in response.headers
    assert "access-control-allow-methods" not in response.headers
    assert "access-control-allow-headers" not in response.headers


def test_active_trace_context_is_propagated_to_backend() -> None:
    http = StubHttpClient(backend_ok)
    context = SpanContext(
        trace_id=0x1234567890ABCDEF1234567890ABCDEF,
        span_id=0x1234567890ABCDEF,
        is_remote=False,
        trace_flags=TraceFlags(TraceFlags.SAMPLED),
    )

    with TestClient(
        build_app(
            config=make_config(),
            session_store=MemorySessionStore(),
            oidc_client=CallbackOidc(),  # type: ignore[arg-type]
            http_client=http,  # type: ignore[arg-type]
        )
    ) as client:
        with trace.use_span(NonRecordingSpan(context)):
            response = client.get("/api/v1/messages")

    backend = [call for call in http.calls if call.url.startswith(BACKEND_ORIGIN)][-1]
    assert response.status_code == 200
    assert backend.headers["traceparent"] == "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
