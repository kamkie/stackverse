# Backend - Spring Boot (Java)

Spring Boot 4.1 - Java 25 - Spring Web MVC - Spring Security (OAuth2 resource
server) - Spring Data JPA - Flyway - PostgreSQL.

Shared behavior (endpoints, env vars, rules) is documented in
[backends/README.md](../README.md) and [docs/SPEC.md](../../docs/SPEC.md). This file
covers only what is specific to this stack.

## Run locally (without Docker)

Infrastructure first, from the repo root: `docker compose up -d` (PostgreSQL :5432,
Keycloak :8180). Then:

```sh
./gradlew bootRun
```

Configuration is environment variables only (see the table in
[backends/README.md](../README.md)); the defaults match the compose infra. The message
seed is read from `../../spec/messages` relative to the working directory. Override
with `SEED_MESSAGES_DIR` when running from anywhere else.

Tests:

```sh
./gradlew build
```

The JUnit suite combines focused service tests with `@WebMvcTest`/MockMvc
controller and security slices. It exercises authorization, blocked-account
handling, validation/problem mapping, bookmark and moderation rules, and
message caching without starting PostgreSQL; `build` also generates the JaCoCo
XML report under `build/reports/jacoco/test/`.

Container image (repo root as context, because the image ships the message seed):

```sh
docker build -t stackverse/backend-spring-java:local -f backends/spring-java/Dockerfile .
```

## Idioms this implementation demonstrates

- **Package by feature, not by layer** - `bookmark/`, `message/`, `moderation/`,
  `account/`, `audit/`, `stats/`; each slice owns its entity, repository, service,
  and controller.
- **Same Spring shape as the Kotlin sibling** - Spring MVC controllers, Spring Data
  JPA repositories/specifications, constructor injection, method security, and a
  resource-server security chain are kept parallel so the comparison isolates Java
  versus Kotlin rather than framework choices.
- **One service behind two API versions** - `BookmarkService` exposes v1 offset
  pagination and v2 keyset pagination over the same underlying query rules.
- **Keyset pagination with Spring Data specifications** - the cursor carries the
  `(createdAt, id)` of the last row, base64url-encoded so clients treat it as opaque.
- **Stateless ETags via `ShallowEtagHeaderFilter`** - message reads and stats hash the
  response body, so any write changes the ETag without instance coordination.
- **Security as configuration, roles as method annotations** - JWT issuer, audience,
  and JWKS validation live in resource-server configuration; controllers name the
  single role they need with `@PreAuthorize`.
- **A custom filter inside the security chain** - `UserAccountFilter` runs after
  bearer-token authentication to lazily upsert the account row and reject blocked
  users before controllers run.
- **Java records for API DTOs** - request and response shapes stay distinct from JPA
  entities while keeping the Java variant compact and type-safe.

## Deliberate deviations & notes

- Programmatic validation mirrors the Kotlin sibling instead of Bean Validation:
  Stackverse validation needs normalization before validation and localized message
  keys from the database.
- Enum query parameters bind through explicit converters in `WebConfig` because the
  wire values are lowercase while Java enum constants are uppercase.
- `/healthz` and `/readyz` are plain controllers (readiness = `select 1`) rather than
  Actuator endpoints, matching the contract paths directly.
- The audit `detail` column is `jsonb` mapped as a JSON string; the API renders it
  back as an object.
- Local tests cover Java-specific helpers, service behavior, and MVC/security
  boundaries with test doubles. The black-box backend conformance suite remains
  the live PostgreSQL contract gate for full API behavior.
- No Java formatter/linter is wired into Gradle yet; the shared `.editorconfig` plus
  `javac` are the current static gate.
- Observability uses the OpenTelemetry Java agent baked into the container image.
  It is inert unless `OTEL_SDK_DISABLED=false` and standard `OTEL_*` variables are set.
- Logging uses Spring Boot structured console logging in ECS flavor. `LOG_FORMAT` is
  mapped in an `EnvironmentPostProcessor` so configuration stays env-only.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) section 10;
`gap` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (Java agent) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ |
| stable `event` names (lifecycle, session, security, moderation) | ✅ |
| dependency events (`dependency_call_failed`, `retry_exhausted`) | ❌ gap - not emitted yet |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (`[action]`/`[nav]`/`[api]`, no field values) | n/a |
