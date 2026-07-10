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

The suite combines focused JUnit tests with `@MicronautTest` HTTP tests that start
the real router, filters, serialization, validation advice, and exception handlers
while replacing external database and identity dependencies with test beans.

Container image (repo root as context - the image ships the message seed):

```sh
docker build -t stackverse/backend-micronaut-java:local -f backends/micronaut-java/Dockerfile .
```

## Idioms this implementation demonstrates

- **Compile-time framework wiring** - controllers, filters, configuration and startup
  listeners are Micronaut beans with annotation processing rather than runtime classpath
  scanning.
- **Blocking work on the blocking executor** - every controller that can reach JDBC,
  plus the authentication filter's JWKS/account path, uses
  `@ExecuteOn(TaskExecutors.BLOCKING)` so Netty event loops never run synchronous I/O.
- **Bean Validation at HTTP boundaries** - typed request records carry Jakarta
  constraints and an injected Micronaut `Validator` performs structural validation at
  the contract-selected point after authentication and resource lookup. A small adapter
  preserves Stackverse's deterministic RFC 9457 field-error keys, while focused JSON
  exception handlers map pre-controller syntax and binding failures to the same problem
  surface; domain-aware normalization and conditional rules remain in controllers.
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
- Micronaut Data was evaluated but is not used: the variant deliberately showcases SQL
  involving PostgreSQL arrays, keyset pagination, row locks, conditional aggregates,
  and multi-step transactions. Wrapping only the trivial statements in repositories
  would create two persistence styles without removing the `Database` boundary.
- `micronaut-security-jwt` / OAuth2 resource-server support is not used. The custom
  `JwtVerifier` keeps issuer, audience, expiry, JWKS refresh, app-account blocking, and
  Stackverse problem mapping visible in one stateless request filter. Its
  `@PreMatching` phase applies the centralized protected-write caller/role policy before
  Micronaut binds JSON bodies, while public reads retain optional bearer handling.
  Framework HTTP tests exercise malformed and wrong-typed payload precedence; adopting
  Micronaut Security would leave the account-state filter and contract-specific error
  mapping custom anyway.
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
