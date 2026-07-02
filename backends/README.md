# Backends

One directory per implementation, named after the stack (`spring-kotlin`, `dotnet`,
`go`, `node-ts`, ...). Every backend implements [spec/openapi.yaml](../spec/openapi.yaml)
with the semantics from [docs/SPEC.md](../docs/SPEC.md).

## Conventions

- **Stateless.** No in-process session, no local caches that affect correctness,
  no sticky-session assumptions. Two instances must be interchangeable.
- **AuthN via JWT.** Validate the bearer token against `OIDC_ISSUER_URI` (JWKS),
  check issuer/signature/expiry/audience. Identity = `preferred_username` claim.
  Never trust identity from headers or bodies.
- **AuthZ via roles.** Endpoints check for the single role they need (`moderator`
  or `admin`) in `realm_access.roles`; a valid token without it gets `403`. The
  hierarchy (admin ⊃ moderator) lives in Keycloak as a composite role — never
  re-implement it in code.
- **Own your schema.** Each backend ships its own migrations (Flyway, EF, goose,
  node-pg-migrate, ...) and applies them on startup. Backends do not share a schema —
  swapping backends means a fresh database, and that's fine.
- **Seed messages on startup.** Import `spec/messages/*.json` idempotently (insert
  missing `(key, language)` pairs only — never overwrite runtime edits). Because the
  seed lives at the repo root, build backend images with the repository root as build
  context: `docker build -f backends/<impl>/Dockerfile .`
- **Errors** are RFC 9457 problem documents (`application/problem+json`).
- **Logging** follows [docs/LOGGING.md](../docs/LOGGING.md): stdout only, OTLP
  export behind `OTEL_SDK_DISABLED`, expected 4xx never logged as errors,
  secrets never logged at all.
- **Listen on `PORT`** (default 8080), expose `/healthz` and `/readyz`.
- **Ship a `Dockerfile`** (multi-stage, non-root user) so the image plugs into
  `compose.yaml` via `BACKEND_IMAGE`.

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP listen port |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `stackverse` | database name |
| `DB_USER` | `stackverse` | database user |
| `DB_PASSWORD` | `stackverse` | database password |
| `OIDC_ISSUER_URI` | `http://localhost:8180/realms/stackverse` | token issuer to validate against |
| `OIDC_JWKS_URI` | *(derived from the issuer via OIDC discovery)* | where to fetch signing keys when the issuer host is not dialable from the container (in compose: `http://keycloak:8080/realms/stackverse/protocol/openid-connect/certs`); `iss` validation still uses `OIDC_ISSUER_URI` |
| `OTEL_SDK_DISABLED` | `true` | set `false` to export traces/metrics/logs over OTLP; standard `OTEL_*` vars (`OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, ...) configure the export (see [docs/RUNNING.md](../docs/RUNNING.md)) |
| `LOG_LEVEL` | `info` | minimum console log severity: `error`, `warn`, `info`, `debug` ([docs/LOGGING.md](../docs/LOGGING.md)) |
| `LOG_FORMAT` | `json` | `text` opts into human-readable console output for local dev ([docs/LOGGING.md](../docs/LOGGING.md)) |

No config files, no profiles-per-environment — the environment is the configuration.

## Per-implementation README

Each implementation's README covers only what is *specific to that stack*: how to run
it locally without Docker, the idioms it demonstrates, and any deliberate deviations
worth discussing in a comparison. The shared behavior is documented once, here and in
the spec — don't repeat it.
