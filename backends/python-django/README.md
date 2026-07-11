# Backend · Python Django + DRF

The Stackverse backend in Python 3.14: **Django** with **Django REST
Framework**, Django ORM models/migrations against PostgreSQL, JWT bearer
authentication with PyJWT against Keycloak's JWKS, and structured stdout
logging. Shared behavior, endpoints, and environment variables are documented
once in [backends/README.md](../README.md) and the contract documents it points
to — this file covers only what is specific to this stack.

## Run it locally

Prerequisites: Python 3.14, the compose infra (`docker compose up -d` at the
repo root).

```sh
cd backends/python-django
python -m venv .venv
. .venv/bin/activate
python -m pip install -e ".[test]"
python -m stackverse_api
```

PowerShell activation uses `.venv\Scripts\Activate.ps1`. Defaults match the
compose infra (PostgreSQL on 5432, Keycloak on 8180); the message seed resolves
to the repo's `spec/messages` automatically. Django migrations apply on startup
before the server listens — the database must be one this backend owns (when
switching from another backend: `docker compose down -v` first, see
[docs/RUNNING.md](../../docs/RUNNING.md)).

Tests require a reachable PostgreSQL server. With the compose service running,
the default `DB_*` values are sufficient; pytest-django creates and tears down
an isolated test database:

```sh
python -m compileall stackverse_api stackverse_django tests
python -m ruff check .
python -m ruff format --check .
python -m pytest --cov=stackverse_api --cov-report=xml
```

The suite uses DRF `APIClient` and the real Django ORM/migrations for bookmark,
message, moderation, account, audit, and stats flows. It also retains focused
units for startup, i18n, authentication/error mapping, and logging. The XML
coverage report is written to `coverage.xml`.

Conformance (the acceptance gate), with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context — the image ships the message seed), from
the repo root:

```sh
docker build -t stackverse/backend-python-django:local -f backends/python-django/Dockerfile .
```

## What this implementation demonstrates

- **Django REST Framework as the HTTP boundary** — DRF parses JSON, dispatches
  function views, and runs a custom authentication class. Route handlers keep
  Stackverse's contract-specific validation keys explicit so RFC 9457 problem
  documents match the shared suite.
- **Django ORM and migrations** — accounts, bookmarks, reports, messages, and
  audit entries are Django models. The migration uses PostgreSQL arrays for
  tags, partial uniqueness for one open report per reporter/bookmark, and
  indexes matching the contract's query paths.
- **Startup-owned schema and seed** — the entrypoint runs `migrate`, logs
  applied migrations as `db_migration_applied`, then imports
  `spec/messages/*.json` idempotently before serving.
- **Stateless body-hash ETags** — message reads and stats hash the serialized
  response body, so writes invalidate old validators without in-process cache
  state.
- **OIDC/JWKS validation at the backend** — `OIDC_JWKS_URI` is honored for
  compose's internal Keycloak address while the `iss` claim is still checked
  against `OIDC_ISSUER_URI`.
- **OpenTelemetry opt-in** — when `OTEL_SDK_DISABLED=false`, Django and psycopg
  instrumentation export traces/metrics/logs over the standard `OTEL_*`
  variables; console logs include trace ids when a span is active.

## Deliberate deviations worth comparing

- DRF serializers are not used for request validation. Stackverse needs
  localized `validation.*` keys, unknown-field tolerance, and identical error
  aggregation across stacks, so small contract-specific validators sit at the
  view edge while Django still owns parsing, auth, ORM, and migrations.
- A few PostgreSQL array aggregations use short SQL snippets (`unnest(tags)`) for
  tag counts. The persistence model remains Django ORM-centered, but this keeps
  PostgreSQL-specific array reporting direct and readable.
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
