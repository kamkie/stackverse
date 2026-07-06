# Backend · Elixir Phoenix

The Stackverse backend on Elixir/OTP: **Phoenix API mode** over Bandit,
Ecto/Postgrex against PostgreSQL, JWT bearer authentication against
Keycloak's JWKS, and structured Logger output. Shared behavior, endpoints,
and environment variables are documented once in [backends/README.md](../README.md)
and the contract documents it points to; this file covers what is specific to
this stack.

## Run it locally

Prerequisites: Elixir 1.18, Erlang/OTP 28, and the compose infra
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
- **Ecto-owned persistence** — the backend owns its migrations and runs them
  on startup; contract-sensitive reads and writes use parameterized SQL
  through `Ecto.Adapters.SQL`.
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

- Persistence uses explicit SQL through Ecto rather than Ecto schemas and
  changesets. The Stackverse contract leans on PostgreSQL array containment,
  row locks, partial unique indexes, and body-stable pagination; keeping those
  SQL shapes visible is more instructive here than hiding them behind schemas.
- Validation is hand-written instead of changeset-based. The API must return
  localized RFC 9457 field errors with Stackverse message keys, and the small
  validator keeps that contract direct.
- The controller is intentionally broad. Phoenix contexts would be natural in a
  product app; this variant keeps the cross-stack comparison points close
  together while helpers (`Validation`, `I18n`, `Cursor`, `Problem`) hold the
  reusable pieces.

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
