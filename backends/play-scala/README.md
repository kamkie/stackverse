# Backend · Play Framework (Scala)

The Stackverse backend in Scala 2.13 on Play Framework 2.9: Play routes/controllers,
Play JSON, plain JDBC through HikariCP, Flyway migrations, PostgreSQL, and Nimbus
JWT validation against Keycloak's JWKS.

Shared behavior, endpoints, and environment variables are documented once in
[backends/README.md](../README.md) and the contract documents it points to. This file
covers only what is specific to this stack.

## Run it locally

Prerequisites: Java 21, sbt 1.12, and the compose infra (`docker compose up -d` at the
repo root).

```sh
cd backends/play-scala
sbt run
```

Defaults match the compose infra (PostgreSQL on 5432, Keycloak on 8180). The message
seed resolves to the repo's `spec/messages` directory; set `SEED_MESSAGES_DIR` if you
run from a different working directory. Migrations apply on startup, so the database
must be one this backend owns. When switching from another backend, reset the compose
database volume first.

Tests:

```sh
sbt test
```

Conformance, with infra and this backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context, so the image ships the message seed):

```sh
docker build -t stackverse/backend-play-scala:local -f backends/play-scala/Dockerfile .
```

## What this implementation demonstrates

- **Conventional Play source layout over raw SQL** — `conf/routes` maps to
  `app/controllers`, while configuration, services, repositories, models, and JSON
  helpers live in their usual `app/*` packages. Business rules stay explicit in
  Scala instead of disappearing into an ORM.
- **Environment-owned configuration** — `PORT`, `DB_*`, `OIDC_*`, `LOG_*`, and
  `SEED_MESSAGES_DIR` are read from the environment; Play's config file only wires
  the framework.
- **PostgreSQL arrays for tags** — `tags text[]` with a GIN index, matching the
  thin SQL variants and keeping tag filtering as array containment.
- **Flyway-owned schema** — migrations live under this implementation and run on
  startup before seed import.
- **Stateless ETags** — message and stats responses hash the serialized body, so
  multiple instances do not coordinate cache validators.
- **Nimbus JWT validation** — signature keys come from `OIDC_JWKS_URI` when set,
  otherwise from OIDC discovery. The expected issuer remains `OIDC_ISSUER_URI`,
  and identity is always `preferred_username`.

## Deliberate deviations worth comparing

- The code remains a compact Play/JDBC service rather than package-by-feature:
  the comparison point here is Play's controller and JSON shape, not a custom
  application framework.
- `LOG_FORMAT=json` controls Stackverse contract events emitted by the application
  logger. Play framework startup lines remain framework-owned console output.
- OpenTelemetry log export uses the Java SDK autoconfiguration path and is active
  only when `OTEL_SDK_DISABLED=false`. HTTP/JDBC tracing is still not wired in code.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (OpenTelemetry Java SDK autoconfigure) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ❌ gap — tracing not wired yet |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ✅¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ (Stackverse events) |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for PostgreSQL readiness failures and OIDC
discovery failures. There are no retry loops, so `retry_exhausted` has no occurrence
to log.
