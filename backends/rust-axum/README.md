# Backend · Rust Axum

The Stackverse backend in Rust 1.96: [Axum](https://github.com/tokio-rs/axum)
over Tokio, SQLx against PostgreSQL, JWT bearer authentication against
Keycloak's JWKS, and `tracing`/OpenTelemetry for structured logs and telemetry.
Shared behavior, endpoints, and environment variables are documented once in
[backends/README.md](../README.md) and the contract documents it points to —
this file covers only what is specific to this stack.

## Run it locally

Prerequisites: Rust 1.96, the compose infra (`docker compose up -d` at the
repo root).

```sh
cd backends/rust-axum
cargo run
```

Defaults match the compose infra (PostgreSQL on 5432, Keycloak on 8180); the
message seed resolves to the repo's `spec/messages` relative to this
directory. Override with `SEED_MESSAGES_DIR` when running from anywhere else.
Migrations apply on startup — the database must be one this backend owns
(when switching from another backend: `docker compose down -v` first, see
[docs/RUNNING.md](../../docs/RUNNING.md)).

Tests:

```sh
cargo fmt --check
cargo check
cargo test
```

Conformance (the acceptance gate), with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context — the image ships the message seed),
from the repo root:

```sh
docker build -t stackverse/backend-rust-axum:local -f backends/rust-axum/Dockerfile .
```

## What this implementation demonstrates

- **Axum as a thin HTTP layer** — routes are explicit async handlers over a
  cloned state object; authentication is one middleware that validates JWTs,
  lazily provisions accounts, and rejects blocked users.
- **SQLx without compile-time database coupling** — runtime-checked queries and
  `FromRow` structs keep CI independent of a live database while preserving
  typed row mapping.
- **Owned migrations** — embedded SQL migrations apply under a PostgreSQL
  advisory lock at startup and emit `db_migration_applied`.
- **JWKS validation** — signing keys are resolved lazily from
  `OIDC_JWKS_URI` or OIDC discovery, cached, and refreshed on unknown key ids.
- **PostgreSQL arrays for tags** — `tags text[]` with a GIN index; repeated tag
  filters use array containment, and tag counts use `unnest`.
- **Stateless ETags** — message and stats reads hash the serialized response
  body, so any write naturally invalidates previous tags without shared memory.
- **OpenTelemetry** — when `OTEL_SDK_DISABLED=false`, spans and log records are
  exported through OTLP/HTTP using standard `OTEL_*` exporter variables; console
  output remains JSON by default.

## Deliberate deviations worth comparing

- The implementation keeps the feature modules in one Rust crate rather than a
  workspace; the comparison point is Axum/SQLx application shape, not crate
  decomposition.
- SQLx 0.9 requires explicit `AssertSqlSafe` for dynamic SQL strings. This
  backend uses it only where the string is assembled from compile-time SQL
  fragments; all request values remain bind parameters.
- Validation is hand-written. The contract wants localized field-level RFC 9457
  problems with all errors collected, which is clearer here than adapting a
  validation framework.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (`opentelemetry-appender-tracing` → OTLP/HTTP) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ (tracing spans are bridged to OpenTelemetry; console includes active span context) |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ✅¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for PostgreSQL failures and Keycloak
OIDC discovery/JWKS failures. There are no retry loops, so `retry_exhausted`
has no occurrence to log.
