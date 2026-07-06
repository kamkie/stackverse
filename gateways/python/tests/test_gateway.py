from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import httpx
from starlette.testclient import TestClient

from stackverse_gateway.app import build_app
from stackverse_gateway.config import GatewayConfig, load_config
from stackverse_gateway.oidc import TokenSet
from stackverse_gateway.security import CONTENT_SECURITY_POLICY, STRICT_TRANSPORT_SECURITY
from stackverse_gateway.sessions import GatewaySession, MemorySessionStore, now_ms

BACKEND_ORIGIN = "http://backend.test"
FRONTEND_ORIGIN = "http://frontend.test"


@dataclass
class Call:
    method: str
    url: str
    headers: httpx.Headers
    body: bytes | None = None


Handler = Callable[[str, str, httpx.Headers, bytes | None], httpx.Response]


class StubHttpClient:
    def __init__(self, handler: Handler | None = None) -> None:
        self.handler = handler or self.default_handler
        self.calls: list[Call] = []

    def build_request(self, method: str, url: str, headers=None, content: bytes | None = None) -> httpx.Request:  # type: ignore[no-untyped-def]
        return httpx.Request(method, url, headers=headers, content=content)

    async def send(self, request: httpx.Request, stream: bool = False) -> httpx.Response:
        body = request.content
        self.calls.append(Call(request.method, str(request.url), request.headers, body))
        response = self.handler(request.method, str(request.url), request.headers, body)
        response.request = request
        return response

    async def get(self, url: str) -> httpx.Response:
        request = httpx.Request("GET", url)
        self.calls.append(Call("GET", url, request.headers))
        response = self.handler("GET", url, request.headers, None)
        response.request = request
        return response

    async def post(self, url: str, data=None, headers=None) -> httpx.Response:  # type: ignore[no-untyped-def]
        request = httpx.Request("POST", url, headers=headers, data=data)
        self.calls.append(Call("POST", url, request.headers, request.content))
        response = self.handler("POST", url, request.headers, request.content)
        response.request = request
        return response

    async def aclose(self) -> None:
        return None

    @staticmethod
    def default_handler(method: str, url: str, _headers: httpx.Headers, _body: bytes | None) -> httpx.Response:
        if url.endswith("/.well-known/openid-configuration"):
            return httpx.Response(
                200,
                json={
                    "authorization_endpoint": f"{BACKEND_IDP}/protocol/openid-connect/auth",
                    "token_endpoint": f"{BACKEND_IDP}/protocol/openid-connect/token",
                    "jwks_uri": f"{BACKEND_IDP}/protocol/openid-connect/certs",
                    "end_session_endpoint": f"{BACKEND_IDP}/protocol/openid-connect/logout",
                },
            )
        if _origin(url) == BACKEND_ORIGIN:
            return httpx.Response(200, json={"ok": True}, headers={"cache-control": "no-cache", "etag": '"bundle-v1"'})
        if _origin(url) == FRONTEND_ORIGIN:
            return httpx.Response(200, text="<h1>Stackverse frontend stub</h1>", headers={"content-type": "text/html"})
        return httpx.Response(404, text="not found")


BACKEND_IDP = "http://idp.test/realms/stackverse"


class StubOidcClient:
    def __init__(self) -> None:
        self.exchange_calls = 0
        self.verify_calls = 0
        self.logout_calls = 0

    async def authorization_url(self, state: str, _code_verifier: str, _nonce: str) -> str:
        return f"{BACKEND_IDP}/protocol/openid-connect/auth?state={state}"

    async def exchange_code(self, code: str, code_verifier: str) -> TokenSet:
        self.exchange_calls += 1
        assert code == "auth-code"
        assert len(code_verifier) >= 40
        return TokenSet("callback-access", 120, refresh_token="callback-refresh", id_token="callback-id")

    async def verify_id_token(self, id_token: str, nonce: str) -> dict[str, str]:
        self.verify_calls += 1
        assert id_token == "callback-id"
        assert len(nonce) >= 30
        return {"preferred_username": "alice"}

    async def logout(self, _refresh_token: str) -> None:
        self.logout_calls += 1

    async def refresh(self, _refresh_token: str) -> TokenSet | None:
        raise AssertionError("refresh should not be called")


def make_config(overrides: dict[str, str] | None = None) -> GatewayConfig:
    return load_config(
        {
            "PORT": "0",
            "BACKEND_URL": BACKEND_ORIGIN,
            "FRONTEND_URL": FRONTEND_ORIGIN,
            "REDIS_URL": "redis://redis.test:6379",
            "OIDC_ISSUER_URI": BACKEND_IDP,
            "OIDC_CLIENT_ID": "stackverse-gateway",
            "OIDC_CLIENT_SECRET": "stackverse-secret",
            "PUBLIC_URL": "http://localhost:8000",
            "OTEL_SDK_DISABLED": "true",
            **(overrides or {}),
        }
    )


def test_reports_anonymous_auth_session_and_issues_xsrf_cookie() -> None:
    store = MemorySessionStore()
    http = StubHttpClient()
    with TestClient(build_app(config=make_config(), session_store=store, http_client=http)) as client:
        response = client.get("/auth/session")

    assert response.status_code == 200
    assert response.json() == {"authenticated": False}
    assert response.cookies.get("XSRF-TOKEN")


def test_builds_oidc_authorization_redirect_with_pkce() -> None:
    http = StubHttpClient()
    with TestClient(build_app(config=make_config(), session_store=MemorySessionStore(), http_client=http)) as client:
        response = client.get("/auth/login", follow_redirects=False)

    assert response.status_code == 302
    location = response.headers["location"]
    params = parse_qs(urlsplit(location).query)
    assert location.startswith(f"{BACKEND_IDP}/protocol/openid-connect/auth")
    assert params["response_type"] == ["code"]
    assert params["code_challenge_method"] == ["S256"]
    assert params["redirect_uri"] == ["http://localhost:8000/auth/callback"]
    assert http.calls[0].url == f"{BACKEND_IDP}/.well-known/openid-configuration"


def test_redirects_failed_callback_home_without_creating_session() -> None:
    store = MemorySessionStore()
    with TestClient(build_app(config=make_config(), session_store=store, http_client=StubHttpClient())) as client:
        response = client.get("/auth/callback?error=access_denied&state=whatever", follow_redirects=False)

    assert response.status_code == 302
    assert response.headers["location"] == "/"
    assert "stackverse_session=" not in response.headers.get("set-cookie", "")


def test_creates_gateway_session_after_successful_callback() -> None:
    store = MemorySessionStore()
    oidc = StubOidcClient()
    with TestClient(
        build_app(config=make_config(), session_store=store, http_client=StubHttpClient(), oidc_client=oidc)  # type: ignore[arg-type]
    ) as client:
        login = client.get("/auth/login", follow_redirects=False)
        state = parse_qs(urlsplit(login.headers["location"]).query)["state"][0]
        callback = client.get(f"/auth/callback?code=auth-code&state={state}", follow_redirects=False)
        session_id = _cookie_value(callback, "stackverse_session")
        session = _await(store.get_session(session_id))
        client.cookies.set("stackverse_session", session_id)
        session_response = client.get("/auth/session")

    assert callback.status_code == 302
    assert callback.headers["location"] == "/"
    assert session is not None
    assert session.username == "alice"
    assert session.access_token == "callback-access"
    assert session.refresh_token == "callback-refresh"
    assert session_response.json() == {"authenticated": True, "username": "alice"}
    assert oidc.exchange_calls == 1
    assert oidc.verify_calls == 1


def test_relays_anonymous_api_requests_without_client_credentials() -> None:
    http = StubHttpClient()
    with TestClient(build_app(config=make_config(), session_store=MemorySessionStore(), http_client=http)) as client:
        response = client.get(
            "/api/v2/bookmarks?visibility=public",
            headers={
                "authorization": "Bearer forged",
                "cookie": "stackverse_session=missing; XSRF-TOKEN=token",
                "proxy-authorization": "Basic forged",
                "te": "trailers",
                "upgrade": "websocket",
            },
        )

    backend = _last_call(http, BACKEND_ORIGIN)
    assert response.status_code == 200
    assert backend.url == f"{BACKEND_ORIGIN}/api/v2/bookmarks?visibility=public"
    assert "authorization" not in backend.headers
    assert "cookie" not in backend.headers
    assert "proxy-authorization" not in backend.headers
    assert "te" not in backend.headers
    assert "upgrade" not in backend.headers


def test_relays_server_side_access_token_and_strips_gateway_headers() -> None:
    store = MemorySessionStore()
    _await(store.save_session("session-id", fresh_session(), 3600))
    http = StubHttpClient()
    with TestClient(build_app(config=make_config(), session_store=store, http_client=http)) as client:
        response = client.get(
            "/api/v1/bookmarks",
            headers={"cookie": "stackverse_session=session-id; XSRF-TOKEN=token", "x-xsrf-token": "token"},
        )

    backend = _last_call(http, BACKEND_ORIGIN)
    assert response.status_code == 200
    assert backend.headers["authorization"] == "Bearer access-token"
    assert "cookie" not in backend.headers
    assert "x-xsrf-token" not in backend.headers


def test_requires_csrf_and_same_origin_browser_signals_for_state_changes() -> None:
    with TestClient(
        build_app(config=make_config(), session_store=MemorySessionStore(), http_client=StubHttpClient())
    ) as client:
        missing = client.post("/api/v1/bookmarks", content=b"{}")
        token = client.cookies["XSRF-TOKEN"]
        foreign_origin = client.post(
            "/api/v1/bookmarks",
            content=b"{}",
            headers={"origin": "https://evil.example", "x-xsrf-token": token},
        )
        same_site = client.post(
            "/api/v1/bookmarks",
            content=b"{}",
            headers={"sec-fetch-site": "same-site", "x-xsrf-token": token},
        )
        valid = client.post(
            "/api/v1/bookmarks",
            content=b"{}",
            headers={"origin": "http://localhost:8000", "x-xsrf-token": token},
        )

    assert missing.status_code == 403
    assert missing.headers["content-type"].startswith("application/problem+json")
    assert foreign_origin.status_code == 403
    assert "Cross-origin" in foreign_origin.text
    assert same_site.status_code == 403
    assert valid.status_code == 200


def test_scopes_security_headers_without_rewriting_api_cache_headers() -> None:
    with TestClient(
        build_app(config=make_config(), session_store=MemorySessionStore(), http_client=StubHttpClient())
    ) as client:
        spa = client.get("/")
        api = client.get("/api/v1/messages/bundle")

    assert spa.headers["x-content-type-options"] == "nosniff"
    assert spa.headers["content-security-policy"] == CONTENT_SECURITY_POLICY
    assert spa.headers["x-frame-options"] == "DENY"
    assert "strict-transport-security" not in spa.headers
    assert api.headers["x-content-type-options"] == "nosniff"
    assert "content-security-policy" not in api.headers
    assert api.headers["cache-control"] == "no-cache"
    assert api.headers["etag"] == '"bundle-v1"'


def test_emits_hsts_only_for_https_public_url() -> None:
    with TestClient(
        build_app(
            config=make_config({"PUBLIC_URL": "https://stackverse.example"}),
            session_store=MemorySessionStore(),
            http_client=StubHttpClient(),
        )
    ) as client:
        response = client.get("/auth/session")

    assert response.headers["strict-transport-security"] == STRICT_TRANSPORT_SECURITY


def test_refresh_rejection_destroys_session_and_degrades_to_anonymous() -> None:
    store = MemorySessionStore()
    _await(store.save_session("session-id", fresh_session(expires_at=now_ms() - 60_000), 3600))

    def handler(method: str, url: str, headers: httpx.Headers, body: bytes | None) -> httpx.Response:
        if url.endswith("/protocol/openid-connect/token"):
            return httpx.Response(400, text="bad grant")
        return StubHttpClient.default_handler(method, url, headers, body)

    http = StubHttpClient(handler)
    with TestClient(build_app(config=make_config(), session_store=store, http_client=http)) as client:
        response = client.get("/api/v1/bookmarks", headers={"cookie": "stackverse_session=session-id"})

    backend = _last_call(http, BACKEND_ORIGIN)
    assert response.status_code == 200
    assert _await(store.get_session("session-id")) is None
    assert "authorization" not in backend.headers


def test_idp_outage_keeps_session_and_returns_503() -> None:
    store = MemorySessionStore()
    _await(store.save_session("session-id", fresh_session(expires_at=now_ms() - 60_000), 3600))

    def handler(method: str, url: str, headers: httpx.Headers, body: bytes | None) -> httpx.Response:
        if url.endswith("/protocol/openid-connect/token"):
            return httpx.Response(503, text="down")
        return StubHttpClient.default_handler(method, url, headers, body)

    http = StubHttpClient(handler)
    with TestClient(build_app(config=make_config(), session_store=store, http_client=http)) as client:
        response = client.get("/api/v1/bookmarks", headers={"cookie": "stackverse_session=session-id"})

    assert response.status_code == 503
    assert response.headers["content-type"].startswith("application/problem+json")
    assert _await(store.get_session("session-id")) is not None
    assert not [call for call in http.calls if _origin(call.url) == BACKEND_ORIGIN]


def test_successful_refresh_updates_session_and_relays_new_token() -> None:
    store = MemorySessionStore()
    _await(store.save_session("session-id", fresh_session(access_token="old", expires_at=now_ms() - 60_000), 3600))

    def handler(method: str, url: str, headers: httpx.Headers, body: bytes | None) -> httpx.Response:
        if url.endswith("/protocol/openid-connect/token"):
            return httpx.Response(
                200, json={"access_token": "new-access", "refresh_token": "new-refresh", "expires_in": 300}
            )
        return StubHttpClient.default_handler(method, url, headers, body)

    http = StubHttpClient(handler)
    with TestClient(build_app(config=make_config(), session_store=store, http_client=http)) as client:
        response = client.get("/api/v1/bookmarks", headers={"cookie": "stackverse_session=session-id"})

    backend = _last_call(http, BACKEND_ORIGIN)
    session = _await(store.get_session("session-id"))
    assert response.status_code == 200
    assert session is not None
    assert session.access_token == "new-access"
    assert session.refresh_token == "new-refresh"
    assert backend.headers["authorization"] == "Bearer new-access"


def test_logout_destroys_local_session_before_best_effort_idp_logout() -> None:
    store = MemorySessionStore()
    _await(store.save_session("session-id", fresh_session(), 3600))
    http = StubHttpClient()
    with TestClient(build_app(config=make_config(), session_store=store, http_client=http)) as client:
        response = client.post("/auth/logout", headers={"cookie": "stackverse_session=session-id"})

    assert response.status_code == 204
    assert _await(store.get_session("session-id")) is None
    assert any(call.url.endswith("/protocol/openid-connect/logout") for call in http.calls)


def test_proxies_frontend_routes_without_gateway_cookies() -> None:
    http = StubHttpClient()
    with TestClient(build_app(config=make_config(), session_store=MemorySessionStore(), http_client=http)) as client:
        response = client.get("/admin/users", headers={"cookie": "stackverse_session=session-id; XSRF-TOKEN=token"})

    frontend = _last_call(http, FRONTEND_ORIGIN)
    assert response.status_code == 200
    assert "Stackverse frontend stub" in response.text
    assert frontend.url == f"{FRONTEND_ORIGIN}/admin/users"
    assert "cookie" not in frontend.headers


def test_serves_static_spa_fallback_for_unknown_and_unsafe_paths(tmp_path: Path) -> None:
    (tmp_path / "assets").mkdir()
    (tmp_path / "index.html").write_text("<main>fallback shell</main>", encoding="utf-8")
    (tmp_path / "assets" / "app.js").write_text("window.stackverse = true;", encoding="utf-8")
    config = make_config({"FRONTEND_URL": "", "SPA_ROOT": str(tmp_path)})

    with TestClient(
        build_app(config=config, session_store=MemorySessionStore(), http_client=StubHttpClient())
    ) as client:
        asset = client.get("/assets/app.js")
        fallback = client.get("/admin/users")
        unsafe = client.get("/%2e%2e/secret.txt")
        unsupported = client.post("/admin/users")

    assert asset.status_code == 200
    assert asset.text == "window.stackverse = true;"
    assert fallback.status_code == 200
    assert fallback.text == "<main>fallback shell</main>"
    assert unsafe.status_code == 200
    assert unsafe.text == "<main>fallback shell</main>"
    assert unsupported.status_code == 404
    assert unsupported.headers["content-type"].startswith("application/problem+json")


def fresh_session(**overrides: object) -> GatewaySession:
    now = now_ms()
    session = GatewaySession(
        username="demo",
        access_token="access-token",
        refresh_token="refresh-token",
        expires_at=now + 60_000,
        created_at=now,
        updated_at=now,
    )
    for key, value in overrides.items():
        setattr(session, key, value)
    return session


def _origin(url: str) -> str:
    parts = urlsplit(url)
    return f"{parts.scheme}://{parts.netloc}"


def _last_call(http: StubHttpClient, origin: str) -> Call:
    matches = [call for call in http.calls if _origin(call.url) == origin]
    assert matches
    return matches[-1]


def _cookie_value(response: httpx.Response, name: str) -> str:
    for cookie in response.headers.get_list("set-cookie"):
        if cookie.startswith(f"{name}="):
            return cookie.split(";", 1)[0].split("=", 1)[1]
    raise AssertionError(f"missing cookie {name}")


def _await(coro):  # type: ignore[no-untyped-def]
    import anyio

    async def runner():  # type: ignore[no-untyped-def]
        return await coro

    return anyio.run(runner)
