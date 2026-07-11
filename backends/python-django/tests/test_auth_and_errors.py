import json
from types import SimpleNamespace

import httpx
import jwt
import pytest
from django.db import DatabaseError

from stackverse_api import auth, exceptions, views
from stackverse_api.auth import Caller, me_response, verify_bearer
from stackverse_api.exceptions import drf_exception_handler
from stackverse_api.models import UserAccount


def test_verify_bearer_validates_contract_claims_and_me_filters_to_application_roles(monkeypatch) -> None:
    signing_key = SimpleNamespace(key="public-key")
    monkeypatch.setattr(
        auth,
        "_client",
        lambda: SimpleNamespace(get_signing_key_from_jwt=lambda token: signing_key),
    )

    def decode(token, key, *, algorithms, audience, issuer):
        assert token == "opaque-token"
        assert key == "public-key"
        assert algorithms == ["RS256"]
        assert audience == "stackverse-api"
        assert issuer == auth.config.oidc_issuer_uri
        return {
            "preferred_username": "alice",
            "name": "Alice Example",
            "email": "alice@example.com",
            "realm_access": {"roles": ["offline_access", "moderator", "admin", 123]},
        }

    monkeypatch.setattr(auth.jwt, "decode", decode)
    caller = verify_bearer("opaque-token")

    assert caller == Caller(
        username="alice",
        roles=["offline_access", "moderator", "admin"],
        name="Alice Example",
        email="alice@example.com",
    )
    assert me_response(caller) == {
        "username": "alice",
        "roles": ["admin", "moderator"],
        "name": "Alice Example",
        "email": "alice@example.com",
    }


def test_jwks_discovery_uses_configured_uri_and_logs_dependency_failure_without_response_data(monkeypatch) -> None:
    monkeypatch.setattr(auth, "config", SimpleNamespace(oidc_jwks_uri="https://idp.example/jwks"))
    assert auth._jwks_uri() == "https://idp.example/jwks"

    monkeypatch.setattr(
        auth,
        "config",
        SimpleNamespace(oidc_jwks_uri=None, oidc_issuer_uri="https://idp.example/realms/stackverse"),
    )
    response = SimpleNamespace(
        raise_for_status=lambda: None,
        json=lambda: {"jwks_uri": "https://idp.example/keys"},
    )
    monkeypatch.setattr(auth.httpx, "get", lambda url, timeout: response)
    assert auth._jwks_uri() == "https://idp.example/keys"

    events: list[tuple[tuple, dict]] = []
    monkeypatch.setattr(auth, "log_event", lambda *args, **fields: events.append((args, fields)))

    def fail(_url, timeout):
        raise httpx.ConnectError(f"unreachable after {timeout}s token=must-not-be-logged")

    monkeypatch.setattr(auth.httpx, "get", fail)
    with pytest.raises(httpx.ConnectError):
        auth._jwks_uri()

    assert events[0][0][1] == "dependency_call_failed"
    assert events[0][1] == {"dependency": "keycloak", "error_code": "oidc_discovery_failed"}
    assert "must-not-be-logged" not in repr(events)


@pytest.mark.django_db
def test_authentication_lazily_provisions_then_rejects_a_blocked_account_with_localized_problem(
    api_client, message_factory, monkeypatch
) -> None:
    monkeypatch.setattr(auth, "verify_bearer", lambda _token: Caller("alice", ["moderator"]))
    events: list[tuple[tuple, dict]] = []
    monkeypatch.setattr(auth, "log_event", lambda *args, **fields: events.append((args, fields)))
    api_client.credentials(HTTP_AUTHORIZATION="Bearer never-log-this-token")

    first = api_client.get("/api/v1/me")
    assert first.status_code == 200
    assert first.json() == {"username": "alice", "roles": ["moderator"]}
    account = UserAccount.objects.get(username="alice")
    first_seen = account.first_seen

    message_factory("error.account.blocked", "en", "This account is blocked.")
    account.status = "blocked"
    account.blocked_reason = "Policy violation"
    account.save(update_fields=["status", "blocked_reason"])

    blocked = api_client.get("/api/v1/me")
    assert blocked.status_code == 403
    assert blocked["Content-Type"] == "application/problem+json"
    assert blocked.json()["detail"] == "This account is blocked."
    account.refresh_from_db()
    assert account.first_seen == first_seen
    assert account.last_seen >= first_seen
    assert events[-1][0][1] == "blocked_user_rejected"
    assert events[-1][1] == {"actor": "alice"}
    assert "never-log-this-token" not in repr(events)
    assert "Policy violation" not in repr(events)


def test_invalid_authorization_and_role_denial_return_problem_documents_without_token_or_query_leakage(
    api_client, client_for, monkeypatch
) -> None:
    events: list[tuple[tuple, dict]] = []
    monkeypatch.setattr(auth, "log_event", lambda *args, **fields: events.append((args, fields)))
    monkeypatch.setattr(auth, "verify_bearer", lambda _token: (_ for _ in ()).throw(jwt.InvalidTokenError("bad")))

    api_client.credentials(HTTP_AUTHORIZATION="Bearer secret-bearer-value")
    invalid = api_client.get("/api/v1/me")
    assert invalid.status_code == 401
    assert invalid["Content-Type"] == "application/problem+json"
    assert events[0][0][1] == "jwt_validation_failed"
    assert events[0][1] == {"error_code": "invalidtokenerror"}
    assert "secret-bearer-value" not in repr(events)

    role_events: list[tuple[tuple, dict]] = []
    monkeypatch.setattr(auth, "log_event", lambda *args, **fields: role_events.append((args, fields)))
    denied = client_for("regular").get("/api/v1/admin/users", {"q": "private-search-term"})
    assert denied.status_code == 403
    assert role_events[0][0][1] == "authz_denied"
    assert role_events[0][1] == {"actor": "regular"}
    assert "private-search-term" not in repr(role_events)


def test_drf_error_mapping_handles_parse_method_and_database_failures_without_exposing_exception_text(
    api_client, client_for, monkeypatch
) -> None:
    parse_events: list[tuple[tuple, dict]] = []
    monkeypatch.setattr(exceptions, "log_event", lambda *args, **fields: parse_events.append((args, fields)))
    admin = client_for("admin", ["admin", "moderator"])
    malformed = admin.generic("POST", "/api/v1/messages", data="{", content_type="application/json")
    assert malformed.status_code == 400
    assert malformed.json()["detail"] == "Request validation failed."
    assert parse_events[0][0][1] == "input_validation_failed"
    assert parse_events[0][1] == {"error_code": "request_parse_failed"}

    method = api_client.patch("/api/v1/messages", {}, format="json")
    assert method.status_code == 405
    assert method["Content-Type"] == "application/problem+json"

    db_events: list[tuple[tuple, dict]] = []
    monkeypatch.setattr(exceptions, "log_event", lambda *args, **fields: db_events.append((args, fields)))
    response = drf_exception_handler(DatabaseError("password=database-secret"), {"request": None})
    assert response.status_code == 500
    assert json.loads(response.content)["detail"] == "An unexpected error occurred."
    assert db_events[0][0][1] == "dependency_call_failed"
    assert db_events[0][1] == {"dependency": "postgres", "error_code": "databaseerror"}
    assert "database-secret" not in repr(db_events)


def test_health_and_readiness_log_only_state_transitions(api_client, monkeypatch) -> None:
    assert api_client.get("/healthz").status_code == 200
    events: list[tuple[tuple, dict]] = []
    recovered: list[str] = []
    monkeypatch.setattr(views, "log_event", lambda *args, **fields: events.append((args, fields)))
    monkeypatch.setattr(views, "logger", SimpleNamespace(info=recovered.append))
    monkeypatch.setattr(views, "_was_ready", True)

    class BrokenConnection:
        @staticmethod
        def cursor():
            raise RuntimeError("database password must not be logged")

    monkeypatch.setattr(views, "connection", BrokenConnection())
    assert api_client.get("/readyz").status_code == 503
    assert api_client.get("/readyz").status_code == 503
    assert len(events) == 1
    assert events[0][0][1] == "dependency_call_failed"
    assert events[0][1]["dependency"] == "postgres"
    assert "database password" not in repr(events)

    class Cursor:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        @staticmethod
        def execute(statement):
            assert statement == "select 1"

    monkeypatch.setattr(views, "connection", SimpleNamespace(cursor=Cursor))
    assert api_client.get("/readyz").status_code == 200
    assert recovered == ["Readiness restored: database reachable again"]
