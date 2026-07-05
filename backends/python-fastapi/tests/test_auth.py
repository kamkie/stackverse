import asyncio
import json

import jwt
import pytest
from starlette.requests import Request

from stackverse_backend import auth
from stackverse_backend.auth import Caller, me_response, require_caller, require_role, verify_bearer
from stackverse_backend.problems import ForbiddenProblem, UnauthorizedProblem


def make_request(authorization: str | None = None, accept_language: str | None = None) -> Request:
    headers = []
    if authorization is not None:
        headers.append((b"authorization", authorization.encode("ascii")))
    if accept_language is not None:
        headers.append((b"accept-language", accept_language.encode("ascii")))
    return Request(
        {
            "type": "http",
            "method": "GET",
            "path": "/api/v1/me",
            "headers": headers,
            "query_string": b"",
            "state": {},
        }
    )


def test_me_response_filters_to_application_roles_and_omits_missing_optional_fields() -> None:
    assert me_response(Caller("demo", ["other", "moderator", "admin"], email="demo@example.com")) == {
        "username": "demo",
        "roles": ["admin", "moderator"],
        "email": "demo@example.com",
    }


def test_require_caller_and_role_raise_contract_problems(monkeypatch) -> None:
    request = make_request()

    with pytest.raises(UnauthorizedProblem):
        require_caller(request)

    request.state.caller = Caller("demo", [])
    events = []
    monkeypatch.setattr(auth, "log_event", lambda *args, **fields: events.append((args, fields)))

    with pytest.raises(ForbiddenProblem):
        require_role(request, "admin")

    assert events[0][0][:3] == ("info", "authz_denied", "denied")
    assert events[0][1]["actor"] == "demo"


def test_verify_bearer_derives_identity_and_roles_from_jwt_claims(monkeypatch) -> None:
    class FakeSigningKey:
        key = "public-key"

    class FakeClient:
        def get_signing_key_from_jwt(self, token):
            assert token == "token"
            return FakeSigningKey()

    def fake_decode(token, key, algorithms, audience, issuer):
        assert (token, key, algorithms, audience, issuer) == (
            "token",
            "public-key",
            ["RS256"],
            auth.config.oidc_audience,
            auth.config.oidc_issuer_uri,
        )
        return {
            "preferred_username": "demo",
            "realm_access": {"roles": ["admin", 42, "moderator"]},
            "name": "Demo User",
            "email": "demo@example.com",
        }

    monkeypatch.setattr(auth, "_client", lambda: FakeClient())
    monkeypatch.setattr(auth.jwt, "decode", fake_decode)

    assert verify_bearer("token") == Caller(
        username="demo",
        roles=["admin", "moderator"],
        name="Demo User",
        email="demo@example.com",
    )


def test_verify_bearer_rejects_tokens_without_username(monkeypatch) -> None:
    class FakeSigningKey:
        key = "public-key"

    class FakeClient:
        def get_signing_key_from_jwt(self, _token):
            return FakeSigningKey()

    monkeypatch.setattr(auth, "_client", lambda: FakeClient())
    monkeypatch.setattr(auth.jwt, "decode", lambda *_args, **_kwargs: {"realm_access": {"roles": ["admin"]}})

    with pytest.raises(jwt.InvalidTokenError, match="missing preferred_username"):
        verify_bearer("token")


def test_authenticate_request_accepts_active_bearer_and_stores_caller(monkeypatch) -> None:
    request = make_request("Bearer token")
    caller = Caller("demo", ["admin"])
    monkeypatch.setattr(auth, "verify_bearer", lambda token: caller if token == "token" else None)
    monkeypatch.setattr(auth, "record_seen", lambda username: "active" if username == "demo" else "blocked")

    response = asyncio.run(auth.authenticate_request(request))

    assert response is None
    assert request.state.caller == caller


def test_authenticate_request_returns_401_problem_for_invalid_bearer(monkeypatch) -> None:
    request = make_request("Bearer invalid")
    monkeypatch.setattr(auth, "verify_bearer", lambda _token: (_ for _ in ()).throw(ValueError("bad token")))
    monkeypatch.setattr(auth, "log_event", lambda *_args, **_fields: None)

    response = asyncio.run(auth.authenticate_request(request))

    assert response.status_code == 401
    assert json.loads(response.body)["detail"] == "Missing or invalid bearer token."
    assert request.state.caller is None


def test_authenticate_request_returns_localized_403_for_blocked_account(monkeypatch) -> None:
    request = make_request("Bearer token", "pl")
    monkeypatch.setattr(auth, "verify_bearer", lambda _token: Caller("blocked-user", []))
    monkeypatch.setattr(auth, "record_seen", lambda _username: "blocked")
    monkeypatch.setattr(auth, "resolve_language", lambda _lang, accept: "pl" if accept == "pl" else "en")
    monkeypatch.setattr(auth, "localize", lambda key, language: f"{key}:{language}")
    monkeypatch.setattr(auth, "log_event", lambda *_args, **_fields: None)

    response = asyncio.run(auth.authenticate_request(request))

    assert response.status_code == 403
    assert json.loads(response.body)["detail"] == "error.account.blocked:pl"
    assert request.state.caller is None
