# Backend · Scala http4s

The Stackverse backend in Scala 3 on http4s Ember and Cats Effect: explicit
`HttpRoutes[IO]`, Cats Effect `Resource` startup/shutdown, HikariCP + raw JDBC
against PostgreSQL, Flyway migrations, Circe JSON, and Nimbus JWT validation
against Keycloak's JWKS.

Shared behavior, endpoints, and environment variables are documented once in
[backends/README.md](../README.md) and the contract documents it points to. This
file covers only what is specific to this stack.

## Run it locally

Prerequisites: Java 21+, sbt 2.0.1, Docker, and the compose infra
(`docker compose up -d` at the repo root) for running the application.

```sh
cd backends/scala-http4s
sbt run
```

Defaults match the compose infra (PostgreSQL on 5432, Keycloak on 8180). The
message seed resolves to the repo's `spec/messages` directory; set
`SEED_MESSAGES_DIR` if you run from a different working directory. Migrations
apply on startup, so the database must be one this backend owns. When switching
from another backend, reset the compose database volume first.

Tests:

```sh
sbt scalafmtCheckAll test
```

The suite is serialized and starts PostgreSQL through Testcontainers, so Docker
is required; compose and Keycloak are not. It combines route/service fakes with
real Flyway/JDBC feature-service integration. Use the CI-equivalent command for
an scoverage report:

```sh
sbt "scalafmtCheckAll; clean; coverage; testFull; coverageReport"
```

`testFull` is intentional under sbt 2: together with the randomized coverage
macro setting in `build.sbt`, it prevents the disk cache from reusing
non-instrumented test output. The XML report is normalized from the generated
`target/**/scoverage-report/scoverage.xml` path by CI.

Conformance, with infra and this backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context, so the image ships the message seed):

```sh
docker build -t stackverse/backend-scala-http4s:local -f backends/scala-http4s/Dockerfile .
```

## What this implementation demonstrates

- **http4s as a small functional HTTP edge** — routes are plain `HttpRoutes[IO]`
  values and every endpoint returns an effect, with no framework-owned mutable
  controller lifecycle.
- **Focused routes and services** — identity, bookmark, message, moderation,
  admin, and health route adapters delegate to matching operation interfaces and
  feature services. `StackverseRoutes` only wires those modules, and `Main` only
  composes resources and starts Ember.
- **Cats Effect resource wiring** — startup loads env config, migrates the
  database, imports message seed files, starts Ember, and closes Hikari/OTel
  resources through `Resource` finalizers. The exact Cats Effect build is pinned
  in `build.sbt` alongside the other implementation-local dependencies.
- **Separated technical boundaries** — environment configuration, boot seeding,
  persistence/row mapping, authentication, i18n, validation, wire codecs,
  logging, and RFC 9457 recovery each have a focused source module.
- **Kleisli authentication middleware** — one http4s `AuthMiddleware` built
  from a `Kleisli` wraps the aggregate API route tree and attaches an optional
  `Caller`. Public operations consume that optional context; protected
  operations require it and pass the authoritative caller into their feature
  service. JWT/account verification therefore happens at most once per API
  request, without one feature's auth boundary intercepting a sibling route.
- **Blocking JDBC isolated in `IO.blocking`** — the HTTP layer stays effect
  typed while the intentionally thin SQL layer uses PostgreSQL arrays and
  `SELECT ... FOR UPDATE` locks for the contract-sensitive races.
- **Flyway-owned schema** — migrations live under this implementation and run
  before seed import.
- **Stateless ETags** — message and stats responses hash the serialized body, so
  multiple instances do not coordinate cache validators.
- **Nimbus JWT validation** — signature keys come from `OIDC_JWKS_URI` when set,
  otherwise from OIDC discovery. The expected issuer remains `OIDC_ISSUER_URI`,
  and identity is always `preferred_username`.
- **Circe response builders** — wire JSON is built explicitly so optional fields
  are omitted exactly where the OpenAPI contract omits them.
- **Route and service tests** — ScalaTest exercises route-to-service behavior
  through fake operation interfaces, including aggregate routing, public
  fallthrough, metadata, and authentication isolation. A serialized
  Testcontainers suite applies the production Flyway schema and covers real
  PostgreSQL row mapping, feature services, transactions, and routed contract
  boundaries.
- **Lifecycle logs follow the complete runtime resource** — startup is emitted
  only after database acquisition, migrations, seed import, and Ember bind;
  shutdown follows resource release. Any acquisition failure is logged once as
  structured `FATAL application_start` with its stack trace and then rethrown.
- **Formatting and warnings as gates** — scalafmt is checked in CI and Scala
  compilation enables deprecation/feature warnings with `-Werror`.

## Deliberate deviations worth comparing

- The persistence layer deliberately retains raw JDBC after evaluating doobie
  and skunk. A narrow `Db`/`Rows` boundary owns connection, transaction,
  parameter-binding, and row-mapping mechanics; feature SQL and transactions
  stay behind focused feature services, and every blocking operation remains
  inside `IO.blocking`.
  This avoids introducing another query DSL solely to express the repository's
  PostgreSQL arrays and lock-sensitive moderation transactions.
- The implementation uses explicit constructor composition and focused operation
  interfaces rather than a full tagless-final algebra hierarchy. This keeps
  route/service seams testable without introducing algebras for every helper.
- JWT verification remains a small Nimbus-backed `AuthService` rather than tsec;
  http4s `AuthMiddleware` owns the HTTP authentication boundary and the service
  keeps blocked-account provisioning next to identity verification.
- Validation and response serialization are hand-written. The contract requires
  accumulated localized field errors and precise optional-field omission, which
  is clearer here than adapting generic derivation.

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
| trace id on console lines when tracing on | ❌ gap — HTTP/JDBC tracing is not wired yet |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ✅¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for PostgreSQL readiness/request failures
and OIDC discovery failures. There are no retry loops, so `retry_exhausted` has
no occurrence to log.
