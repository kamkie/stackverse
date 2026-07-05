# Backend - Micronaut (Java)

Micronaut 5.0 · Java 25 · Gradle · Netty · HikariCP/JDBC · Flyway · PostgreSQL.

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

Tests:

```sh
./gradlew test
```

Container image (repo root as context - the image ships the message seed):

```sh
docker build -t stackverse/backend-micronaut-java:local -f backends/micronaut-java/Dockerfile .
```

## Idioms this implementation demonstrates

- **Compile-time framework wiring** - controllers, filters, configuration and startup
  listeners are Micronaut beans with annotation processing rather than runtime classpath
  scanning.
- **Plain JDBC as the persistence boundary** - SQL is visible in the feature controllers
  and helpers, with Flyway owning the schema and HikariCP owning connections.
- **Custom bearer-token filter** - the service validates issuer, audience, expiry and
  JWKS signatures itself, then stores the derived identity on the request for explicit
  role checks.
- **Lowercase wire values in storage** - enum-like columns store the OpenAPI values
  directly, and tags use a PostgreSQL `text[]` plus a GIN index.
- **Stateless response ETags** - message and stats reads hash the serialized response
  body, so any write invalidates the representation without a shared cache or version
  counter.

## Deliberate deviations & notes

- Micronaut Management is not used for `/healthz` and `/readyz`; the contract wants two
  exact paths, with readiness checking `select 1`.
- `micronaut-security-jwt` / OAuth2 resource-server support is not used. The custom
  `JwtVerifier` keeps issuer, audience, expiry and JWKS validation visible for
  cross-stack comparison while still preserving the stateless backend contract.
- The OpenTelemetry Java agent is baked into the container image for traces, metrics and
  logs when `OTEL_SDK_DISABLED=false`; local `./gradlew run` is console-only unless run
  with an agent by hand.
- Logging is configured programmatically before Micronaut starts so `LOG_LEVEL` and
  `LOG_FORMAT` stay environment-only. JSON is the default; `LOG_FORMAT=text` is for
  local development.
- "Unresolvable cursor -> 400" is interpreted as undecodable: a well-formed cursor whose
  boundary row was deleted can still be a valid stateless keyset position.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (Java agent in container) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ (Java agent MDC) |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ❌ gap - JWKS/OIDC failures are logged; DB retry/exhaustion events are not emitted yet |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |
