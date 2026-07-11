# Backend · Elixir Phoenix

The Stackverse backend on Elixir/OTP: **Phoenix API mode** over Bandit,
Ecto/Postgrex against PostgreSQL, JWT bearer authentication against
Keycloak's JWKS, and structured Logger output. Shared behavior, endpoints,
and environment variables are documented once in [backends/README.md](../README.md)
and the contract documents it points to; this file covers what is specific to
this stack.

## Run it locally

Prerequisites: Elixir 1.20, Erlang/OTP 29, and the compose infra
(`docker compose up -d` at the repo root).

```sh
cd backends/elixir-phoenix
mix deps.get
mix run --no-halt
```

Defaults match the compose infra (PostgreSQL on 5432, Keycloak on 8180); the
message seed resolves to the repo's `spec/messages` automatically. Ecto
migrations apply on startup. The database must be one this backend owns; when
switching from another backend, wipe the compose database volume first as
described in [docs/RUNNING.md](../../docs/RUNNING.md).

Tests:

```sh
mix format --check-formatted
mix compile --warnings-as-errors
mix test
```

CI also starts PostgreSQL and runs database-tagged authenticated `ConnCase`
HTTP tests plus `DataCase` context, seed, and persistence regressions. To
include those locally, point the standard `DB_*` variables at a test database,
set `STACKVERSE_DB_TESTS=true`, run `MIX_ENV=test mix ecto.migrate`, and then
run `MIX_ENV=test mix test`. Use `MIX_ENV=test mix coveralls.lcov` for the
CI-equivalent coverage report at `cover/lcov.info`; the suite also checks
structured-error logging and secret exclusion.

Conformance (the acceptance gate), with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context because the image ships the message
seed), from the repo root:

```sh
docker build -t stackverse/backend-elixir-phoenix:local -f backends/elixir-phoenix/Dockerfile .
```

## What this implementation demonstrates

- **Phoenix as a thin API boundary** — the router is explicit and a single
  authentication plug validates JWTs, lazily provisions accounts, and rejects
  blocked users before controller actions run.
- **Contexts and focused controllers** — bookmark, message, report,
  moderation, account, audit, and stats contexts own application/data
  behavior; focused Phoenix controllers own only HTTP parsing, authorization,
  and response rendering. Shared input, persistence-error, and JSON-view
  support keeps contract-sensitive boundary rules in one place.
- **Ecto-owned persistence and input schemas** — typed schemas, `Ecto.Query`,
  and `Repo` own normal bookmark, message, report, account, and audit
  persistence. Embedded schemas use `cast/4` before normalization and map cast
  failures to the established Stackverse message keys.
- **BEAM supervision shape** — Repo starts under the application supervisor;
  migrations and message seed run before the Endpoint is added, so `/readyz`
  is not exposed until the schema exists.
- **JWKS validation with JOSE** — signing keys are resolved lazily from
  `OIDC_JWKS_URI` or OIDC discovery and cached in-process for verification.
- **PostgreSQL arrays for tags** — `tags text[]` with a GIN index; repeated
  tag filters use array containment, and tag counts use `unnest`.
- **Stateless ETags** — message and stats reads hash the serialized response
  body, so writes invalidate tags without shared memory or per-instance state.

## Deliberate deviations worth comparing

- Normal persistence uses `Ecto.Schema`, `Ecto.Query`, changesets, constraints,
  and `Repo` CRUD. Explicit SQL is limited to three PostgreSQL-shaped
  aggregates/imports: array `unnest` for tag counts, timezone-aware daily
  statistics, and the bulk message-seed `unnest`/`on conflict` insert. Tag
  containment remains one reviewed Ecto `fragment`; row locks and partial
  unique constraints use Ecto query/changeset APIs.
- Responses are explicit maps converted from persistence structs. This keeps
  lowercase wire enums, camel-case field names, and RFC 9457 omission rules at
  the JSON boundary without replacing the typed persistence model.
- `ConnCase` endpoint/parser tests, `DataCase` input/schema tests, and
  PostgreSQL-backed Repo/query regressions cover Phoenix/Ecto boundaries; the
  shared black-box conformance suite owns exhaustive live behavior.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ❌ gap: console JSON is implemented; OTLP log export is not wired yet |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ❌ gap: OpenTelemetry trace/log correlation is not wired yet |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ✅¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for Keycloak discovery/JWKS failures.
PostgreSQL startup failures fail the release before the endpoint is exposed;
there are no retry loops, so `retry_exhausted` has no occurrence to log.
