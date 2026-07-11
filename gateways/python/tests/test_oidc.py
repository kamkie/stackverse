from __future__ import annotations

import time
from collections.abc import Awaitable
from dataclasses import dataclass
from typing import Any
from urllib.parse import parse_qs

import anyio
import httpx
import pytest
from joserfc import jwt
from joserfc.errors import BadSignatureError, InvalidClaimError
from joserfc.jwk import RSAKey

from stackverse_gateway.config import GatewayConfig, load_config
from stackverse_gateway.logging import configure_logging
from stackverse_gateway.oidc import (
    IdpUnavailableError,
    OidcClient,
    TokenSet,
    parse_token_set,
    username_from_id_token,
)

PUBLIC_ISSUER = "https://identity.example/realms/stackverse"
INTERNAL_ISSUER = "http://keycloak:8080/realms/stackverse"


@dataclass
class HttpCall:
    method: str
    url: str
    data: dict[str, str] | None = None


class StubHttpClient:
    def __init__(self, handler):  # type: ignore[no-untyped-def]
        self._handler = handler
        self.calls: list[HttpCall] = []

    async def get(self, url: str) -> httpx.Response:
        self.calls.append(HttpCall("GET", url))
        return self._response("GET", url, None)

    async def post(self, url: str, data=None, headers=None) -> httpx.Response:  # type: ignore[no-untyped-def]
        del headers
        normalized = dict(data) if data is not None else None
        self.calls.append(HttpCall("POST", url, normalized))
        return self._response("POST", url, normalized)

    def _response(self, method: str, url: str, data: dict[str, str] | None) -> httpx.Response:
        response = self._handler(method, url, data)
        if isinstance(response, Exception):
            raise response
        response.request = httpx.Request(method, url)
        return response


def make_config(overrides: dict[str, str] | None = None) -> GatewayConfig:
    return load_config(
        {
            "BACKEND_URL": "http://backend.test",
            "REDIS_URL": "redis://redis.test:6379",
            "OIDC_ISSUER_URI": PUBLIC_ISSUER,
            "OIDC_INTERNAL_ISSUER_URI": INTERNAL_ISSUER,
            "OIDC_CLIENT_ID": "stackverse-gateway",
            "OIDC_CLIENT_SECRET": "client-secret-value",
            "PUBLIC_URL": "https://stackverse.example",
            "OTEL_SDK_DISABLED": "true",
            **(overrides or {}),
        }
    )


def discovery_document() -> dict[str, str]:
    return {
        "authorization_endpoint": f"{PUBLIC_ISSUER}/protocol/openid-connect/auth",
        "token_endpoint": f"{PUBLIC_ISSUER}/protocol/openid-connect/token",
        "jwks_uri": f"{PUBLIC_ISSUER}/protocol/openid-connect/certs",
        "end_session_endpoint": f"{PUBLIC_ISSUER}/protocol/openid-connect/logout",
    }


def run[T](awaitable: Awaitable[T]) -> T:
    async def runner() -> T:
        return await awaitable

    return anyio.run(runner)


def test_metadata_keeps_browser_authorization_public_and_rewrites_server_endpoints() -> None:
    http = StubHttpClient(lambda _method, _url, _data: httpx.Response(200, json=discovery_document()))
    client = OidcClient(make_config(), http)  # type: ignore[arg-type]

    metadata = run(client.metadata())
    cached = run(client.metadata())
    authorization_url = run(client.authorization_url("state-value", "verifier-value", "nonce-value"))

    assert metadata.authorization_endpoint == f"{PUBLIC_ISSUER}/protocol/openid-connect/auth"
    assert metadata.token_endpoint == f"{INTERNAL_ISSUER}/protocol/openid-connect/token"
    assert metadata.jwks_uri == f"{INTERNAL_ISSUER}/protocol/openid-connect/certs"
    assert metadata.end_session_endpoint == f"{INTERNAL_ISSUER}/protocol/openid-connect/logout"
    assert cached is metadata
    assert [call.method for call in http.calls] == ["GET"]
    query = parse_qs(httpx.URL(authorization_url).query.decode())
    assert query["state"] == ["state-value"]
    assert query["nonce"] == ["nonce-value"]
    assert query["redirect_uri"] == ["https://stackverse.example/auth/callback"]
    assert query["code_challenge_method"] == ["S256"]
    assert query["code_challenge"][0] != "verifier-value"


def test_metadata_uses_contract_endpoints_when_discovery_omits_them() -> None:
    http = StubHttpClient(lambda _method, _url, _data: httpx.Response(200, json={}))

    metadata = run(OidcClient(make_config(), http).metadata())  # type: ignore[arg-type]

    assert metadata.authorization_endpoint == f"{PUBLIC_ISSUER}/protocol/openid-connect/auth"
    assert metadata.token_endpoint == f"{INTERNAL_ISSUER}/protocol/openid-connect/token"
    assert metadata.jwks_uri == f"{INTERNAL_ISSUER}/protocol/openid-connect/certs"
    assert metadata.end_session_endpoint == f"{INTERNAL_ISSUER}/protocol/openid-connect/logout"


def test_exchange_code_posts_pkce_and_client_credentials_to_internal_endpoint() -> None:
    def handler(method: str, url: str, _data: dict[str, str] | None) -> httpx.Response:
        if method == "GET":
            return httpx.Response(200, json=discovery_document())
        assert url == f"{INTERNAL_ISSUER}/protocol/openid-connect/token"
        return httpx.Response(
            200,
            json={
                "access_token": "access-value",
                "refresh_token": "refresh-value",
                "id_token": "id-value",
                "expires_in": 180,
            },
        )

    http = StubHttpClient(handler)

    tokens = run(OidcClient(make_config(), http).exchange_code("authorization-code", "pkce-verifier"))  # type: ignore[arg-type]

    assert tokens == TokenSet("access-value", 180, "refresh-value", "id-value")
    token_call = http.calls[-1]
    assert token_call.data == {
        "grant_type": "authorization_code",
        "code": "authorization-code",
        "redirect_uri": "https://stackverse.example/auth/callback",
        "client_id": "stackverse-gateway",
        "client_secret": "client-secret-value",
        "code_verifier": "pkce-verifier",
    }


def test_exchange_code_rejects_token_endpoint_failure() -> None:
    def handler(method: str, _url: str, _data: dict[str, str] | None) -> httpx.Response:
        return httpx.Response(200, json=discovery_document()) if method == "GET" else httpx.Response(400)

    with pytest.raises(RuntimeError, match="token_endpoint_400"):
        run(OidcClient(make_config(), StubHttpClient(handler)).exchange_code("code", "verifier"))  # type: ignore[arg-type]


@pytest.mark.parametrize("failure", ["network", "status"])
def test_discovery_failure_is_classified_as_idp_unavailable(failure: str) -> None:
    def handler(_method: str, url: str, _data: dict[str, str] | None) -> httpx.Response | Exception:
        if failure == "network":
            return httpx.ConnectError("connection refused", request=httpx.Request("GET", url))
        return httpx.Response(503)

    with pytest.raises(IdpUnavailableError, match="OIDC discovery"):
        run(OidcClient(make_config(), StubHttpClient(handler)).metadata())  # type: ignore[arg-type]


def test_verifies_rs256_signature_issuer_audience_and_nonce_and_caches_jwks() -> None:
    key = RSAKey.generate_key(auto_kid=True)
    claims = {
        "iss": PUBLIC_ISSUER,
        "aud": "stackverse-gateway",
        "nonce": "expected-nonce",
        "preferred_username": "alice",
        "iat": int(time.time()),
        "exp": int(time.time()) + 300,
    }
    id_token = jwt.encode({"alg": "RS256", "kid": key.kid}, claims, key)

    def handler(_method: str, url: str, _data: dict[str, str] | None) -> httpx.Response:
        if url.endswith("/.well-known/openid-configuration"):
            return httpx.Response(200, json=discovery_document())
        return httpx.Response(200, json={"keys": [key.as_dict()]})

    http = StubHttpClient(handler)
    client = OidcClient(make_config(), http)  # type: ignore[arg-type]

    first = run(client.verify_id_token(id_token, "expected-nonce"))
    second = run(client.verify_id_token(id_token, "expected-nonce"))

    assert first["preferred_username"] == "alice"
    assert second == first
    assert len([call for call in http.calls if call.url.endswith("/certs")]) == 1


def test_rejects_id_token_whose_signature_does_not_match_public_jwks() -> None:
    trusted_key = RSAKey.generate_key(auto_kid=True)
    attacker_key = RSAKey.generate_key(auto_kid=True)
    claims = {
        "iss": PUBLIC_ISSUER,
        "aud": "stackverse-gateway",
        "nonce": "expected-nonce",
        "sub": "alice-id",
        "exp": int(time.time()) + 300,
    }
    id_token = jwt.encode({"alg": "RS256", "kid": trusted_key.kid}, claims, attacker_key)

    def handler(_method: str, url: str, _data: dict[str, str] | None) -> httpx.Response:
        if url.endswith("/.well-known/openid-configuration"):
            return httpx.Response(200, json=discovery_document())
        return httpx.Response(200, json={"keys": [trusted_key.as_dict()]})

    with pytest.raises(BadSignatureError):
        run(OidcClient(make_config(), StubHttpClient(handler)).verify_id_token(id_token, "expected-nonce"))  # type: ignore[arg-type]


def test_jwks_endpoint_failure_is_classified_as_idp_unavailable() -> None:
    def handler(_method: str, url: str, _data: dict[str, str] | None) -> httpx.Response:
        if url.endswith("/.well-known/openid-configuration"):
            return httpx.Response(200, json=discovery_document())
        return httpx.Response(503)

    with pytest.raises(IdpUnavailableError, match="JWKS endpoint returned 503"):
        run(OidcClient(make_config(), StubHttpClient(handler)).verify_id_token("not-a-token", "nonce"))  # type: ignore[arg-type]


@pytest.mark.parametrize(
    ("claim", "invalid_value"),
    [
        ("iss", "https://evil.example/realms/stackverse"),
        ("aud", "another-client"),
        ("nonce", "replayed-nonce"),
    ],
)
def test_rejects_id_token_with_invalid_bound_claim(claim: str, invalid_value: str) -> None:
    key = RSAKey.generate_key(auto_kid=True)
    claims = {
        "iss": PUBLIC_ISSUER,
        "aud": "stackverse-gateway",
        "nonce": "expected-nonce",
        "sub": "alice-id",
        "exp": int(time.time()) + 300,
        claim: invalid_value,
    }
    id_token = jwt.encode({"alg": "RS256", "kid": key.kid}, claims, key)

    def handler(_method: str, url: str, _data: dict[str, str] | None) -> httpx.Response:
        if url.endswith("/.well-known/openid-configuration"):
            return httpx.Response(200, json=discovery_document())
        return httpx.Response(200, json={"keys": [key.as_dict()]})

    with pytest.raises(InvalidClaimError):
        run(OidcClient(make_config(), StubHttpClient(handler)).verify_id_token(id_token, "expected-nonce"))  # type: ignore[arg-type]


@pytest.mark.parametrize("status", [400, 401])
def test_refresh_rejection_returns_none_without_leaking_credentials(
    status: int, capsys: pytest.CaptureFixture[str]
) -> None:
    configure_logging(make_config())

    def handler(method: str, _url: str, _data: dict[str, str] | None) -> httpx.Response:
        return httpx.Response(200, json=discovery_document()) if method == "GET" else httpx.Response(status)

    result = run(OidcClient(make_config(), StubHttpClient(handler)).refresh("refresh-secret-value"))  # type: ignore[arg-type]

    captured = capsys.readouterr().out
    assert result is None
    assert '"event":"token_refresh_failed"' in captured
    assert '"idp_status":' + str(status) in captured
    assert "refresh-secret-value" not in captured
    assert "client-secret-value" not in captured


@pytest.mark.parametrize("failure", ["rate-limited", "network", "malformed"])
def test_refresh_outage_keeps_session_semantics_and_logs_no_credentials(
    failure: str, capsys: pytest.CaptureFixture[str]
) -> None:
    configure_logging(make_config())

    def handler(method: str, url: str, _data: dict[str, str] | None) -> httpx.Response | Exception:
        if method == "GET":
            return httpx.Response(200, json=discovery_document())
        if failure == "rate-limited":
            return httpx.Response(429)
        if failure == "network":
            return httpx.ConnectError("connection refused", request=httpx.Request("POST", url))
        return httpx.Response(200, content=b"not-json")

    with pytest.raises(IdpUnavailableError):
        run(OidcClient(make_config(), StubHttpClient(handler)).refresh("refresh-secret-value"))  # type: ignore[arg-type]

    captured = capsys.readouterr().out
    assert '"event":"dependency_call_failed"' in captured
    assert '"dependency":"keycloak"' in captured
    assert "refresh-secret-value" not in captured
    assert "client-secret-value" not in captured


@pytest.mark.parametrize("failure", ["status", "network"])
def test_logout_is_best_effort_and_never_logs_the_refresh_token(
    failure: str, capsys: pytest.CaptureFixture[str]
) -> None:
    configure_logging(make_config())

    def handler(method: str, url: str, _data: dict[str, str] | None) -> httpx.Response | Exception:
        if method == "GET":
            return httpx.Response(200, json=discovery_document())
        if failure == "network":
            return httpx.ConnectError("connection refused", request=httpx.Request("POST", url))
        return httpx.Response(503)

    run(OidcClient(make_config(), StubHttpClient(handler)).logout("refresh-secret-value"))  # type: ignore[arg-type]

    captured = capsys.readouterr().out
    assert '"event":"idp_logout_failed"' in captured
    assert "refresh-secret-value" not in captured
    assert "client-secret-value" not in captured


@pytest.mark.parametrize(
    ("payload", "expected"),
    [
        ({"preferred_username": "preferred", "name": "name", "sub": "sub"}, "preferred"),
        ({"preferred_username": "", "name": "name", "sub": "sub"}, "name"),
        ({"name": 42, "sub": "sub"}, "sub"),
    ],
)
def test_username_uses_the_first_non_empty_identity_claim(payload: dict[str, Any], expected: str) -> None:
    assert username_from_id_token(payload) == expected


def test_username_requires_a_string_identity_claim() -> None:
    with pytest.raises(RuntimeError, match="id_token_missing_username"):
        username_from_id_token({"preferred_username": "", "name": None, "sub": 7})


def test_token_response_defaults_invalid_lifetime_and_discards_non_string_optional_tokens() -> None:
    assert parse_token_set(
        {"access_token": "access", "expires_in": -1, "refresh_token": 123, "id_token": ["not", "a", "token"]}
    ) == TokenSet("access", 300)


@pytest.mark.parametrize("value", [None, [], {}, {"access_token": ""}, {"access_token": 42}])
def test_token_response_requires_a_non_empty_access_token(value: Any) -> None:
    with pytest.raises(ValueError):
        parse_token_set(value)
