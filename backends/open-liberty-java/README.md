# Backend · Open Liberty Java (Maven)

The Stackverse backend on **Open Liberty** with Java 21 bytecode and Maven:
CDI-managed Jakarta REST endpoints over explicit JDBC/Flyway persistence,
MicroProfile JWT authentication against Keycloak, typed record DTOs with Bean
Validation, and the Liberty Maven plugin managing the local server runtime.
Shared behavior, endpoints, and environment variables are documented once in
[backends/README.md](../README.md) and the contract documents it points to —
this file covers only what is specific to this stack.

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

Build, unit tests, style check, and packaged-Liberty integration test:

```sh
./mvnw verify
```

`verify` creates a minimized Liberty distribution containing the configured
features and application WAR, then Failsafe inspects that archive. Use
`./mvnw spotless:apply` after intentional Java formatting changes.

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
  using an injected application-scoped JDBC boundary, HikariCP, and
  Flyway-owned migrations.
- **Container-managed facilities** — CDI owns configuration, lifecycle,
  persistence, logging, message lookup, and request filters; JAX-RS providers
  and resources are discovered from annotations rather than a manual class
  registry.
- **MicroProfile JWT** — Open Liberty validates issuer, audience, lifetime,
  signature, and `preferred_username`. A name-bound `@RequiresRole` JAX-RS
  authorization filter maps the established Keycloak `realm_access.roles`
  claim without changing the shared token shape.
- **Typed and validated payloads** — public request and response shapes are
  Java records. Jakarta Bean Validation constraints feed the Stackverse
  localized field-error mapper; raw maps remain only for intentionally open
  JSON audit details and internal SQL/audit assembly.
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

- Explicit JDBC is retained instead of Jakarta Persistence so SQL locking,
  PostgreSQL arrays, and the moderation state machine remain directly
  comparable with the non-ORM variants. Pooling, migrations, transactions, and
  statement binding are isolated behind the injected `JdbcRepository` facade;
  its container-owned `RuntimeSupport` controls the pool lifecycle.
- Standard `@RolesAllowed` expects a top-level MP JWT groups claim, while the
  repository-wide Keycloak contract places roles in `realm_access.roles`.
  `@RequiresRole` is the variant-local declarative adapter applied after
  Liberty validates the token; sibling backends and token contents are not
  changed.
- Bean Validation is invoked through the injected `Validator` at the
  contract-defined point in each operation rather than eagerly with `@Valid`.
  This preserves authorization and not-found precedence while still using the
  container's validation provider and constraint metadata.
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

¹ JWT discovery/JWKS traffic belongs to Open Liberty's MicroProfile JWT
facility rather than an application-owned dependency client. Database startup,
readiness, and request failures emit `dependency_call_failed` with latency and a
stable error code. There are no retry loops, so `retry_exhausted` has no
occurrence to log.
