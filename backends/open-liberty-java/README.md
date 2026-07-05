# Backend · Open Liberty Java (Maven)

The Stackverse backend on **Open Liberty** with Java 21 bytecode and Maven:
Jakarta REST endpoints over explicit JDBC/Flyway persistence, JWT bearer
authentication against Keycloak's JWKS, and the Liberty Maven plugin managing
the local server runtime. Shared behavior, endpoints, and environment variables
are documented once in [backends/README.md](../README.md) and the contract
documents it points to — this file covers only what is specific to this stack.

## Run it locally

Prerequisites: Java 21+ and the compose infra (`docker compose up -d` at the
repo root). A separately installed Liberty server is not required; Maven
downloads and configures the runtime.

```sh
cd backends/open-liberty-java
./mvnw liberty:dev
```

For a foreground run without dev-mode recompilation:

```sh
./mvnw package liberty:run
```

Defaults match the compose infra (PostgreSQL on 5432, Keycloak on 8180); the
message seed resolves to the repo's `spec/messages` automatically. Migrations
apply on startup — the database must be one this backend owns (when switching
from another backend: `docker compose down -v` first, see
[docs/RUNNING.md](../../docs/RUNNING.md)).

Build/tests:

```sh
./mvnw test
```

Conformance (the acceptance gate), with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context — the image ships the message seed),
from the repo root:

```sh
docker build -t stackverse/backend-open-liberty-java:local -f backends/open-liberty-java/Dockerfile .
```

## What this implementation demonstrates

- **Liberty as a build-managed runtime** — `liberty:dev` / `liberty:run`
  download Open Liberty and run the WAR from Maven, so local development does
  not depend on an external app-server installation.
- **Jakarta REST over explicit SQL** — the runtime supplies the servlet/JAX-RS
  boundary; Stackverse behavior stays in one self-contained implementation
  using JDBC, HikariCP, and Flyway-owned migrations.
- **Manual JWT validation** — Nimbus validates issuer, audience, expiry, and
  signature from `OIDC_JWKS_URI` or OIDC discovery, while identity and roles
  come only from `preferred_username` and `realm_access.roles`.
- **Flyway on Liberty classloaders** — the migration and callback resource
  directories include `flyway.location` marker files, which Flyway requires to
  resolve classpath locations correctly under WebSphere/Open Liberty.
- **PostgreSQL arrays for tags** — tags are stored as `text[]` with a GIN
  index, matching the Go/Node storage idiom and contrasting with the
  Spring-Kotlin join-table variant.
- **Body-hash ETags** — message and stats responses hash the deterministic JSON
  body, keeping cache revalidation stateless across backend instances.
- **Container telemetry path** — the Docker image includes the OpenTelemetry
  Java agent, inert by default with `OTEL_SDK_DISABLED=true` and activated by
  the standard `OTEL_*` environment variables.

## Deliberate deviations worth comparing

- The implementation keeps the Jakarta boundary thin rather than leaning on
  Jakarta Persistence or Bean Validation. That makes the moderation state
  machine, lock ordering, and contract-specific validation messages explicit.
- The local Maven run emits the app's structured stdout logs directly. OTLP log
  export is enabled in the container path through the Java agent; local
  developers can add the same agent with `MAVEN_OPTS` when they need telemetry.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (container Java agent; local opt-in via `MAVEN_OPTS`) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ (OpenTelemetry API reads the active span when the agent is enabled) |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ✅¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for OIDC discovery failures. Database
startup failures fail the app before listening; readiness checks expose database
availability through `/readyz`. There are no retry loops, so `retry_exhausted`
has no occurrence to log.
