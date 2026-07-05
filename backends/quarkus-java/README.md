# Backend · Quarkus Java (Maven)

The Stackverse backend in Java 21 with Quarkus 3.37, Maven, RESTEasy Reactive,
JDBC against PostgreSQL, Flyway migrations, SmallRye JWT bearer authentication,
structured JSON logging, OpenTelemetry export, and container packaging.

Shared behavior, endpoints, and environment variables are documented once in
[backends/README.md](../README.md) and the contract documents it points to; this
file covers only what is specific to this stack.

## Run it locally

Prerequisites: Java 21+, Maven, and the compose infra
(`docker compose up -d` at the repo root).

```sh
cd backends/quarkus-java
mvn quarkus:dev
```

Defaults match the compose infra (PostgreSQL on 5432, Keycloak on 8180). The
message seed resolves to the repo's `spec/messages` relative to this directory;
override with `SEED_MESSAGES_DIR` when running from anywhere else. Migrations
apply on startup, so wipe the compose database volume when switching from
another backend implementation.

Tests:

```sh
mvn verify
```

Conformance (the acceptance gate), with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context because the image ships the message seed),
from the repo root:

```sh
docker build -t stackverse/backend-quarkus-java:local -f backends/quarkus-java/Dockerfile .
```

The same image definition also lives at Quarkus' conventional generated-project
location, `src/main/docker/Dockerfile.jvm`, for developers who expect the
standard Quarkus layout.

## What this implementation demonstrates

- **Quarkus build-time wiring with explicit JDBC** — REST resources and CDI are
  Quarkus-native, while SQL remains visible and contract-shaped.
- **One resource over the full contract** — the routes share helpers for
  pagination, RFC 9457 problem documents, localization, ETags, audit writes, and
  transaction handling.
- **JWT validation by configuration** — SmallRye JWT validates issuer, audience,
  signature, and expiry from `OIDC_ISSUER_URI` / `OIDC_JWKS_URI`; the code derives
  identity from `preferred_username` and roles from `realm_access.roles`.
- **Flyway-owned schema** — migrations run at startup and log applied migrations
  with the contract event name.
- **Stateless body-hash ETags** — message reads and stats hash the JSON response
  body, so any write changes the tag without in-process coordination.
- **Runtime-managed seed import** — `spec/messages/*.json` is imported
  idempotently on startup; existing runtime edits are never overwritten.

## Deliberate deviations worth comparing

- The implementation uses plain JDBC rather than Hibernate/Panache. That makes the
  Quarkus variant a useful contrast to Spring Data JPA and keeps row locking for
  moderation flows explicit.
- Enum wire values are stored lowercase in PostgreSQL, matching the API contract
  directly.
- `LOG_FORMAT=text` is mapped to Quarkus' JSON-console switch through a small
  MicroProfile `ConfigSource` so the repo-standard env var remains the only
  logging format control.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (Quarkus OpenTelemetry) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ❌ gap — database and Keycloak dependency failures rely on framework errors today |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |
