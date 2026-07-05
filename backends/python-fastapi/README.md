# Backend · Python FastAPI

The Stackverse backend in Python 3.14: **FastAPI** with plain `psycopg`
queries against PostgreSQL, JWT bearer authentication with PyJWT against
Keycloak's JWKS, and structured stdout logging. Shared behavior, endpoints,
and environment variables are documented once in [backends/README.md](../README.md)
and the contract documents it points to — this file covers only what is
specific to this stack.

## Run it locally

Prerequisites: Python 3.14, the compose infra (`docker compose up -d` at the
repo root).

```sh
cd backends/python-fastapi
python -m venv .venv
. .venv/bin/activate
python -m pip install -e ".[test]"
python -m stackverse_backend
```

PowerShell activation uses `.venv\Scripts\Activate.ps1`. Defaults match the
compose infra (PostgreSQL on 5432, Keycloak on 8180); the message seed resolves
to the repo's `spec/messages` automatically. Migrations apply on startup — the
database must be one this backend owns (when switching from another backend:
`docker compose down -v` first, see [docs/RUNNING.md](../../docs/RUNNING.md)).

Tests (plain unit tests, no containers):

```sh
python -m compileall stackverse_backend tests
python -m pytest
```

Conformance (the acceptance gate), with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context — the image ships the message seed),
from the repo root:

```sh
docker build -t stackverse/backend-python-fastapi:local -f backends/python-fastapi/Dockerfile .
```

## What this implementation demonstrates

- **FastAPI with a thin service layer** — handlers use FastAPI routing and
  middleware, but request bodies are validated by contract-specific functions
  so RFC 9457 problem documents carry the localized `validation.*` keys the
  shared suite asserts.
- **Plain SQL with psycopg** — feature logic stays close to PostgreSQL:
  arrays for tags, partial unique indexes for one open report per reporter,
  row locks for the moderation state machine, and keyset pagination over
  `(created_at, id)`.
- **Hand-rolled startup migrations** — SQL files under `migrations/` are
  applied under a PostgreSQL advisory lock and recorded in
  `schema_migrations`, logging `db_migration_applied` for each new file.
- **Stateless body-hash ETags** — message reads and stats hash the serialized
  response body, so any write invalidates old validators without in-process
  cache state.
- **OIDC/JWKS validation at the backend** — `OIDC_JWKS_URI` is honored for
  compose's internal Keycloak address while the `iss` claim is still checked
  against `OIDC_ISSUER_URI`.
- **OpenTelemetry opt-in** — when `OTEL_SDK_DISABLED=false`, FastAPI and
  psycopg instrumentation export traces/metrics/logs over the standard
  `OTEL_*` variables; console logs include trace ids when a span is active.

## Deliberate deviations worth comparing

- The migration runner is intentionally small instead of Alembic. Stackverse
  uses one owned schema per backend and automatic startup migrations, so a
  lock-protected SQL runner keeps the operational behavior explicit while
  still demonstrating Python's database tooling surface.
- Sync `psycopg` calls are used from FastAPI's worker threadpool. The code is
  easier to read for a contract-first comparison, while authentication
  middleware moves blocking verification/account provisioning off the event
  loop.
- Optional response fields are omitted rather than serialized as `null`, using
  small response mappers instead of a global serializer policy.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (OpenTelemetry SDK logging handler) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ✅¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for PostgreSQL readiness/request failures
and Keycloak discovery/JWKS failures. There are no retry loops, so
`retry_exhausted` has no occurrence to log.
