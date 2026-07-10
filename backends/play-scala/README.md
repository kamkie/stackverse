# Backend · Play Framework (Scala)

The Stackverse backend in Scala 3 on Play Framework 3.0.11: Play
routes/controllers on Apache Pekko, typed Play JSON, plain JDBC through HikariCP,
Flyway migrations, PostgreSQL, and Nimbus JWT validation against Keycloak's JWKS.

Shared behavior, endpoints, and environment variables are documented once in
[backends/README.md](../README.md) and the contract documents it points to. This file
covers only what is specific to this stack.

## Run it locally

Prerequisites: Java 21, sbt 1.12, and the compose infra (`docker compose up -d` at the
repo root).

```sh
cd backends/play-scala
sbt run
```

Defaults match the compose infra (PostgreSQL on 5432, Keycloak on 8180). The message
seed resolves to the repo's `spec/messages` directory; set `SEED_MESSAGES_DIR` if you
run from a different working directory. `APPLICATION_SECRET` can override Play's
local-development application secret for deployed runs. Migrations apply on startup,
so the database must be one this backend owns. When switching from another backend,
reset the compose database volume first.

Tests:

```sh
sbt scalafmtCheckAll test
```

The Scalafmt config uses `project.git = true`; a brand-new untracked Scala file
is therefore outside `scalafmtAll`. Add it with intent-to-add (`git add -N`) or
otherwise make it tracked before formatting, then run `scalafmtCheckAll`.

Conformance, with infra and this backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context, so the image ships the message seed):

```sh
docker build -t stackverse/backend-play-scala:local -f backends/play-scala/Dockerfile .
```

## What this implementation demonstrates

- **Play 3 on Apache Pekko** — the application uses the current open-source Play
  line and its `org.playframework` sbt plugin, with no Akka runtime dependency.
- **Focused injected controllers** — `conf/routes` maps health, identity, bookmark,
  message, moderation, and admin resources to separate Guice-constructed
  controllers. Each controller owns its feature's endpoint orchestration and SQL,
  while a small bookmark lookup repository owns the one query shared with
  moderation. Configuration, authentication, i18n, input/wire codecs, and
  cross-cutting action/audit concerns remain focused collaborators under
  conventional `app/*` packages.
- **Typed Play JSON boundaries** — request case classes have contract-aware `Reads`
  that are total, retain localized RFC 9457 message keys in `JsError`, and map
  errors once at the action boundary. Production controller results use row
  `OWrites`, which continue to omit absent optional fields.
- **Environment-owned configuration** — `PORT`, `DB_*`, `OIDC_*`, `LOG_*`, and
  `SEED_MESSAGES_DIR` are read from the environment; Play's config file only wires
  the framework.
- **Guice-owned component wiring** — configuration, logging, database access,
  i18n, auth, startup, the `ApiAction` action builder, audit recording, and
  controllers are constructor-injected components rather than a hand-built
  singleton graph.
- **Play-native action and auth boundary** — controllers select the injected
  builder's optional-caller, authenticated-caller, or role-filtered action.
  `ActionRefiner`/`ActionFilter` composition authenticates before feature code;
  the base builder centralizes localized RFC 9457 recovery, structured stack
  logging for unexpected failures, and bounded `database-dispatcher` offload.
- **PostgreSQL arrays for tags** — `tags text[]` with a GIN index, matching the
  thin SQL variants and keeping tag filtering as array containment.
- **Flyway-owned schema** — migrations live under this implementation and run on
  startup before seed import.
- **Stateless ETags** — message and stats responses hash the serialized body, so
  multiple instances do not coordinate cache validators.
- **Centralized live localization** — `I18n` owns query/header negotiation and
  batches validation-message fallback in one query. Supported languages are not
  cached because admin message writes can add a language at runtime.
- **Nimbus JWT validation** — signature keys come from `OIDC_JWKS_URI` when set,
  otherwise from OIDC discovery. The expected issuer remains `OIDC_ISSUER_URI`,
  and identity is always `preferred_username`. Nimbus's default unknown-key
  refetch limiter remains explicitly enabled: it reserves the normal rotation
  refresh while bounding attacker-driven JWKS traffic.
- **ScalaTestPlusPlay application testing** — Guice application tests compile the
  router documentation without executing DB-backed actions, reject a residual
  all-feature service, and exercise auth, dispatcher, and RFC 9457 boundaries
  without a database; focused tests cover total codecs and SQL/wire helpers.
- **Warnings and formatting as gates** — compilation uses `-Werror`, and scalafmt is
  checked locally and in the component workflow.

## Deliberate deviations worth comparing

- Feature controllers intentionally use the thin raw-JDBC `Db` helper rather than
  adopting Slick or Anorm. The shared `ApiAction` owns only Play action/error
  mechanics and dispatcher offloading; feature orchestration stays in the owning
  controller.
- `ApiAction` recovers domain failures and unexpected `NonFatal` exceptions. It
  intentionally does not catch JVM-fatal errors such as `OutOfMemoryError` or
  `StackOverflowError`; Pekko/JVM shutdown is safer than returning a 500 from a
  process whose runtime integrity is no longer reliable.
- `LOG_FORMAT=json` controls Stackverse contract events emitted by the application
  logger. Play framework startup lines remain framework-owned console output.
- OpenTelemetry log export uses the Java SDK autoconfiguration path and is active
  only when `OTEL_SDK_DISABLED=false`. HTTP/JDBC tracing is still not wired in code.

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
| trace id on console lines when tracing on | ❌ gap — tracing not wired yet |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ✅¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ (Stackverse events) |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for PostgreSQL readiness/request failures
and OIDC discovery failures; request failures include duration and stack trace.
Unexpected non-fatal 5xx responses are also logged at `ERROR` with stack and
duration. There are no retry loops, so `retry_exhausted` has no occurrence to log.
