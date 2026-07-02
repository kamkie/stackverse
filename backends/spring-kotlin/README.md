# Backend — Spring Boot (Kotlin)

Spring Boot 4.1 · Kotlin 2.3 · Java 21 · Spring Web MVC · Spring Security (OAuth2
resource server) · Spring Data JPA · Flyway · PostgreSQL.

Shared behavior (endpoints, env vars, rules) is documented in
[backends/README.md](../README.md) and [docs/SPEC.md](../../docs/SPEC.md) — this file
covers only what is specific to this stack.

## Run locally (without Docker)

Infrastructure first, from the repo root: `docker compose up -d` (PostgreSQL :5432,
Keycloak :8180). Then:

```sh
./gradlew bootRun
```

Configuration is environment variables only (see the table in
[backends/README.md](../README.md)); the defaults match the compose infra. The message
seed is read from `../../spec/messages` relative to the working directory — override
with `SEED_MESSAGES_DIR` when running from anywhere else.

Tests need Docker (Testcontainers starts its own PostgreSQL; no Keycloak needed —
authentication is injected as pre-built JWTs):

```sh
./gradlew test
```

Container image (repo root as context — the image ships the message seed):

```sh
docker build -t stackverse/backend-spring-kotlin:local -f backends/spring-kotlin/Dockerfile .
```

## Idioms this implementation demonstrates

- **Package by feature, not by layer** — `bookmark/`, `message/`, `moderation/`,
  `account/`, `audit/`, `stats/`; each slice owns its entity, repository, service,
  and controller.
- **One service behind two API versions** — `BookmarkService` exposes `listOffset`
  (v1) and `listKeyset` (v2) over the same specification; the controllers are thin
  representations of the same logic, which is the point of the v1 → v2 exhibit.
- **Keyset pagination with Spring Data specifications** — the cursor is the
  `(createdAt, id)` of the last row, compared with a `(a < x) or (a = x and b < y)`
  predicate; `Instant.now()` is truncated to microseconds so in-memory values always
  round-trip through PostgreSQL's `timestamptz` unchanged.
- **Stateless ETags via `ShallowEtagHeaderFilter`** — the ETag is a hash of the
  response body, so any write changes it and two instances never need to coordinate.
- **Security as configuration, roles as method annotations** — the resource server
  validates issuer/audience/signature via JWKS from properties alone;
  `@PreAuthorize("hasRole('moderator')")` on controllers names the single role each
  endpoint needs (the admin ⊃ moderator hierarchy stays in Keycloak).
- **A custom filter inside the security chain** — `UserAccountFilter` sits right
  after bearer-token authentication to upsert the account row (lazy provisioning)
  and reject blocked users before any controller runs.
- **Programmatic validation instead of Bean Validation** — field rules live in the
  services because the contract demands normalization *before* validation (tags are
  trimmed/lowercased first) and error messages localized from the database, which
  annotation-driven validation cannot express cleanly.

## Deliberate deviations & notes

- Enum request parameters (`visibility=public`) bind through explicit converters in
  `WebConfig` because the wire values are lowercase while Kotlin enum constants are not.
- `/healthz` and `/readyz` are plain controllers (readiness = `select 1`) rather than
  Actuator endpoints — the contract's paths and semantics are simpler than what
  Actuator's health groups provide.
- The audit `detail` column is `jsonb` mapped as a JSON string; the API renders it
  back as an object.
- Logging (docs/LOGGING.md) uses Spring Boot's built-in structured console
  logging in its **ECS flavor** — UTC timestamps, and both the MDC (where the
  OTel Java agent puts `trace_id`/`span_id`) and SLF4J key-value pairs (how the
  contract's `event`/`outcome` fields are attached) land as JSON fields. The
  `LOG_FORMAT` mapping lives in an `EnvironmentPostProcessor` so configuration
  stays env-only — no `logback.xml`.
- "Unresolvable cursor → 400" is interpreted as *undecodable*: a well-formed but
  never-issued cursor is indistinguishable, on a stateless service, from a legitimate
  cursor whose boundary row was deleted between pages — and that one must keep
  working. Only cursors that fail to decode into a `(createdAt, id)` position are
  rejected.
