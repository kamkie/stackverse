# Backend - Ktor (Kotlin)

Ktor 3.5 · Kotlin 2.4 · Java 25 · Netty · JDBC/HikariCP · Flyway · PostgreSQL.

Shared behavior (endpoints, env vars, rules) is documented in
[backends/README.md](../README.md) and [docs/SPEC.md](../../docs/SPEC.md) - this file
covers only what is specific to this stack.

## Run locally (without Docker)

Infrastructure first, from the repo root: `docker compose up -d` (PostgreSQL :5432,
Keycloak :8180). Then:

```sh
./gradlew run
```

Configuration is environment variables only (see the table in
[backends/README.md](../README.md)); the defaults match the compose infra. The message
seed is read from `../../spec/messages` relative to the working directory - override
with `SEED_MESSAGES_DIR` when running from anywhere else.

Kotlin style check, build, unit tests, and PostgreSQL integration tests:

```sh
./gradlew ktlintCheck build
```

The integration suite uses Testcontainers PostgreSQL plus a loopback JWKS
issuer to exercise the real Ktor module, Flyway/Hikari/JDBC persistence, signed
tokens, authorization, and contract workflows. Docker is required; no live
Keycloak is needed. The test task finalizes JaCoCo and writes XML to
`build/reports/jacoco/test/jacocoTestReport.xml`.

Container image (repo root as context - the image ships the message seed):

```sh
docker build -t stackverse/backend-ktor-kotlin:local -f backends/ktor-kotlin/Dockerfile .
```

## Idioms this implementation demonstrates

- **Ktor as a thin HTTP boundary** - routing, status pages, JSON negotiation, and
  auth/account handling live in application plugins and routes; contract decisions stay
  in explicit service/repository functions.
- **Ktor-native dependency injection** - `ktor-server-di` constructor providers wire
  repositories and services by type; the container also closes the Hikari data source
  with the application lifecycle.
- **Coroutine-friendly blocking persistence** - JDBC/HikariCP calls run on
  `Dispatchers.IO`, keeping the implementation easy to read while avoiding event-loop
  blocking.
- **Flyway on startup** - the Ktor process owns and applies its schema before it
  listens, matching the backend convention without a framework container.
- **Lazy JWKS validation** - tokens are validated with Nimbus against
  `OIDC_ISSUER_URI` plus `OIDC_JWKS_URI` or OIDC discovery, so the service can start
  while Keycloak is still booting.
- **Programmatic logging configuration** - Logback is configured from `LOG_LEVEL` and
  `LOG_FORMAT` in code, so logging stays environment-driven without per-environment
  config files.

## Deliberate deviations & notes

- Persistence is plain SQL instead of an ORM. This keeps transaction boundaries,
  row locks, and the report-resolution state machine visible in the code, which is
  useful comparison material next to Spring Data JPA.
- ETags for message reads and stats are stateless response-body hashes. Any write that
  changes the representation changes the ETag, and two instances never coordinate.
- The OpenTelemetry Java agent is baked into the container image and activated only
  when `OTEL_SDK_DISABLED=false`, mirroring the Spring Kotlin backend's container
  behavior.
- "Unresolvable cursor -> 400" is interpreted as undecodable: a well-formed but
  never-issued cursor is indistinguishable, on a stateless service, from a legitimate
  cursor whose boundary row was deleted between pages.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (Java agent in the container image) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ❌ gap - JWKS discovery/fetch failures are surfaced as startup/auth failures but not yet normalized as dependency events |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |
