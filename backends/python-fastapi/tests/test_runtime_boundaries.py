from __future__ import annotations

import asyncio
import json
import logging
from types import SimpleNamespace

import psycopg
import pytest
from fakes import ScriptedConnection, Step, scripted_transaction
from fastapi.testclient import TestClient

from stackverse_backend import auth, db, logging_setup, main
from stackverse_backend.auth import authenticate_request
from stackverse_backend.problems import UnauthorizedProblem
from stackverse_backend.routers import messages


def test_migrations_apply_new_files_in_order_skip_existing_and_always_unlock(tmp_path, monkeypatch) -> None:
    (tmp_path / "002_already.sql").write_text("create table already_present (id int)", encoding="utf-8")
    (tmp_path / "001_new.sql").write_text("create table newly_applied (id int)", encoding="utf-8")
    connection = ScriptedConnection(
        Step("select pg_advisory_lock"),
        Step("create table if not exists schema_migrations"),
        Step("select 1 from schema_migrations", one=None, params=("001_new.sql",)),
        Step("create table newly_applied"),
        Step("insert into schema_migrations", params=("001_new.sql",)),
        Step("select 1 from schema_migrations", one={"exists": 1}, params=("002_already.sql",)),
        Step("select pg_advisory_unlock"),
    )
    monkeypatch.setattr(db, "_migrations_dir", lambda: tmp_path)
    monkeypatch.setattr(db, "transaction", lambda: scripted_transaction(connection))
    events = []
    monkeypatch.setattr(db, "log_event", lambda *args, **fields: events.append((args, fields)))

    db.run_migrations()

    assert [event[1]["migration"] for event in events] == ["001_new.sql"]
    connection.assert_exhausted()

    (tmp_path / "002_already.sql").unlink()
    failing = ScriptedConnection(
        Step("select pg_advisory_lock"),
        Step("create table if not exists schema_migrations"),
        Step("select 1 from schema_migrations", one=None),
        Step("create table newly_applied", raises=RuntimeError("migration failed")),
        Step("select pg_advisory_unlock"),
    )
    monkeypatch.setattr(db, "transaction", lambda: scripted_transaction(failing))

    with pytest.raises(RuntimeError, match="migration failed"):
        db.run_migrations()
    failing.assert_exhausted()


def test_migration_directory_prefers_environment_and_rejects_missing_sql(tmp_path, monkeypatch) -> None:
    migrations = tmp_path / "migrations"
    migrations.mkdir()
    (migrations / "001.sql").write_text("select 1", encoding="utf-8")
    monkeypatch.setenv("MIGRATIONS_DIR", str(migrations))

    assert db._migrations_dir() == migrations

    (migrations / "001.sql").unlink()
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(db.Path, "resolve", lambda self: tmp_path / "package" / "db.py")
    with pytest.raises(RuntimeError, match="Migration directory not found"):
        db._migrations_dir()


def test_oidc_discovery_lazy_provisioning_and_invalid_token_logs_are_privacy_safe(monkeypatch) -> None:
    config = SimpleNamespace(
        oidc_jwks_uri=None,
        oidc_issuer_uri="https://idp.example/realms/stackverse",
        oidc_audience="stackverse-api",
    )
    monkeypatch.setattr(auth, "config", config)

    class Response:
        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict[str, str]:
            return {"jwks_uri": "https://idp.example/jwks"}

    monkeypatch.setattr(auth.httpx, "get", lambda url, timeout: Response())
    assert auth._jwks_uri() == "https://idp.example/jwks"

    config.oidc_jwks_uri = "https://internal-keycloak/jwks"
    assert auth._jwks_uri() == "https://internal-keycloak/jwks"

    now = object()
    seen_calls = []
    monkeypatch.setattr(auth, "now_utc", lambda: now)
    monkeypatch.setattr(
        auth, "query", lambda statement, params: seen_calls.append((statement, params)) or [{"status": "blocked"}]
    )
    assert auth.record_seen("demo") == "blocked"
    assert seen_calls[0][1] == ("demo", now, now)

    events = []
    monkeypatch.setattr(auth, "verify_bearer", lambda _token: (_ for _ in ()).throw(ValueError("signed-secret")))
    monkeypatch.setattr(auth, "log_event", lambda *args, **fields: events.append((args, fields)))
    request = auth.Request(
        {
            "type": "http",
            "method": "GET",
            "path": "/api/v1/me",
            "headers": [(b"authorization", b"Bearer actual-secret-token")],
            "query_string": b"",
        }
    )

    with pytest.raises(UnauthorizedProblem):
        asyncio.run(auth.authenticate_request(request))

    serialized_event = repr(events[0])
    assert events[0][0][:3] == ("info", "jwt_validation_failed", "failure")
    assert "actual-secret-token" not in serialized_event
    assert "signed-secret" not in serialized_event


def test_lifespan_orders_startup_shutdown_and_never_logs_database_credentials(monkeypatch) -> None:
    order = []
    events = []
    monkeypatch.setattr(main, "open_pool", lambda: order.append("open_pool"))
    monkeypatch.setattr(main, "run_migrations", lambda: order.append("migrate"))
    monkeypatch.setattr(main, "seed_messages", lambda: order.append("seed"))
    monkeypatch.setattr(main, "close_pool", lambda: order.append("close_pool"))
    monkeypatch.setattr(main, "shutdown_telemetry", lambda: order.append("shutdown_telemetry"))
    monkeypatch.setattr(main, "log_event", lambda *args, **fields: events.append((args, fields)))

    with TestClient(main.build_app()) as client:
        assert client.get("/healthz").status_code == 200
        order.append("request")

    assert order == ["open_pool", "migrate", "seed", "request", "close_pool", "shutdown_telemetry"]
    assert [event[0][1] for event in events] == ["application_start", "application_stop"]
    startup = repr(events[0])
    assert "db_password" not in startup
    assert "password=" not in startup


def test_framework_and_database_errors_use_rfc9457_without_leaking_driver_details(monkeypatch) -> None:
    app = main.build_app()
    app.dependency_overrides[authenticate_request] = lambda: None
    client = TestClient(app, raise_server_exceptions=False)

    missing = client.get("/does-not-exist")
    wrong_method = client.post("/healthz")
    assert (missing.status_code, missing.json()["title"]) == (404, "Not Found")
    assert (wrong_method.status_code, wrong_method.json()["title"]) == (405, "Method Not Allowed")
    assert missing.headers["content-type"].startswith("application/problem+json")

    events = []
    monkeypatch.setattr(main, "log_event", lambda *args, **fields: events.append((args, fields)))
    monkeypatch.setattr(
        messages,
        "query",
        lambda *_args: (_ for _ in ()).throw(psycopg.OperationalError("password authentication failed: secret")),
    )
    failed = client.get("/api/v1/messages")

    assert failed.status_code == 500
    assert failed.json()["detail"] == "An unexpected error occurred."
    assert "secret" not in failed.text
    assert events[0][0][:3] == ("error", "dependency_call_failed", "failure")
    assert events[0][1] == {"dependency": "postgres", "error_code": "operationalerror"}


def test_json_formatter_emits_one_line_structured_trace_context(monkeypatch) -> None:
    context = SimpleNamespace(is_valid=True, trace_id=0xA, span_id=0xB)
    monkeypatch.setattr(
        logging_setup.trace, "get_current_span", lambda: SimpleNamespace(get_span_context=lambda: context)
    )
    record = logging.LogRecord("stackverse.test", logging.INFO, __file__, 1, "safe\nmessage", (), None)
    record.event = "report_resolved"
    record.outcome = "success"

    rendered = logging_setup.JsonFormatter().format(record)
    payload = json.loads(rendered)

    assert "\n" not in rendered
    assert payload["message"] == "safe\nmessage"
    assert payload["event"] == "report_resolved"
    assert payload["outcome"] == "success"
    assert payload["trace_id"] == f"{10:032x}"
    assert payload["span_id"] == f"{11:016x}"
