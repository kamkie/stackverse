# Backend - Grails (Groovy)

Grails 7.1 · Groovy · Java 25 · Spring Security resource server · JDBC ·
Flyway · PostgreSQL.

Shared behavior, endpoints, and environment variables are documented in
[backends/README.md](../README.md) and the contract documents it points to.
This file covers only what is specific to the Grails variant.

## Run locally

Infrastructure first, from the repo root: `docker compose up -d` (PostgreSQL
:5432, Keycloak :8180). Then:

```sh
cd backends/grails
./gradlew bootRun
```

Configuration is environment variables only; defaults match compose. The
message seed is read from `../../spec/messages` relative to this directory,
or from `SEED_MESSAGES_DIR` when set.

Tests:

```sh
./gradlew test
```

Conformance, with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context, so `spec/messages` is packaged):

```sh
docker build -t stackverse/backend-grails:local -f backends/grails/Dockerfile .
```

## What this implementation demonstrates

- **Grails controllers and services over a contract-first API** - URL mappings
  stay explicit because the route contract is fixed by OpenAPI, not by resource
  scaffolding.
- **Spring resource-server JWT validation inside Grails** - issuer, JWKS, and
  audience validation use Spring Security; controllers ask for `moderator` or
  `admin` directly and leave role hierarchy to Keycloak.
- **JDBC persistence with Grails services** - SQL is kept in feature services so
  the moderation state machine, keyset predicate, partial unique index, and
  audit writes are visible comparison material.
- **Flyway-owned schema** - this backend has its own lowercase wire-value schema
  and applies migrations on startup.
- **Stateless ETags** - message and stats reads hash deterministic JSON bodies,
  so revalidation works across instances without cache coordination.
- **Java-agent observability** - the container includes the OpenTelemetry Java
  agent, inert unless `OTEL_SDK_DISABLED=false` and standard `OTEL_*` variables
  are set.

## Deliberate deviations worth comparing

- The implementation uses JDBC rather than GORM domain classes. That keeps the
  contract-heavy SQL behavior explicit while still using Grails controllers,
  services, configuration, and packaging.
- Enum-like values are stored as lowercase wire strings (`public`, `open`,
  `hidden`) to avoid a mapping layer between database and API.
- Malformed UUID path segments answer `404`; the contract pins valid UUID
  behavior, and this matches the "cannot name an existing resource" reading used
  by the lighter backends.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (Java agent) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ (Java agent MDC) |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ❌ gap - not emitted yet |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |
