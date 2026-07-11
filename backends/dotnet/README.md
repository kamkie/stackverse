# Backend · ASP.NET Core (C#)

The Stackverse backend on .NET 10: minimal APIs, EF Core with Npgsql, JWT bearer
authentication against Keycloak's JWKS. Shared behavior, endpoints, and
environment variables are documented once in [backends/README.md](../README.md)
and the contract documents it points to — this file covers only what is
specific to this stack.

## Run it locally

Prerequisites: .NET SDK 10, the compose infra
(`docker compose up -d` at the repo root).

```sh
cd backends/dotnet
dotnet run --project src/StackverseBackend
```

Defaults match the compose infra (PostgreSQL on 5432, Keycloak on 8180); the
message seed resolves to the repo's `spec/messages` automatically. EF Core
migrations apply on startup — the database must be one this backend owns
(when switching from another backend: `docker compose down -v` first, see
[docs/RUNNING.md](../../docs/RUNNING.md)).

Tests (unit tests plus WebApplicationFactory integration tests; no containers):

```sh
dotnet format StackverseBackend.slnx --verify-no-changes
dotnet test --collect:"XPlat Code Coverage"
```

The in-process suite covers account authorization, bookmark/message/moderation
flows, audit and problem mapping, persistence-failure translation, and
structured logging. It deliberately does not prove Npgsql-specific SQL,
migrations/readiness, real uniqueness races, live JWKS, process lifecycle, or
OTLP bootstrap; the live PostgreSQL conformance suite remains the acceptance
gate for those API behaviors.

`Directory.Build.props` enables the .NET 10 recommended SDK analyzers, build-time
code-style checks, and warnings-as-errors for both projects. CI additionally runs
the solution-wide `dotnet format` verification shown above. The local
`.editorconfig` keeps only two generated/test idioms out of that gate: EF Core
migration artifacts and underscore-separated xUnit test names.

NuGet resolves through committed `packages.lock.json` files. After changing
`PackageReference` versions or local tools, run `dotnet restore --force-evaluate`
from this directory and commit the updated lock files.

Conformance (the acceptance gate), with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context — the image ships the message seed),
from the repo root:

```sh
docker build -t stackverse/backend-dotnet:local -f backends/dotnet/Dockerfile .
```

## What this implementation demonstrates

- **Minimal APIs over controllers** — endpoint groups per feature
  (`Bookmarks/`, `Messages/`, `Moderation/`, ...), services carrying the
  business rules, no MVC layer. The idiomatic .NET 10 shape for a service of
  this size.
- **EF Core as the schema owner** — the model in `Data/AppDbContext.cs` maps
  to explicit snake_case DDL; `dotnet ef migrations add` generates the checked-in
  migrations (a local tool, `dotnet tool restore`), and `Database.Migrate()`
  applies them at startup.
- **PostgreSQL arrays for tags** — `tags text[]` with a GIN index instead of a
  join table; tag filters translate to array containment, tag counts go
  through `unnest`. A deliberate contrast with spring-kotlin's
  `bookmark_tags` collection table — same wire behavior, different storage
  idiom.
- **Keyset pagination in LINQ** — the v2 cursor listing is the same
  `(created_at, id)` walk as the reference backend, composed as `IQueryable`
  predicates.
- **Fallback authorization policy** — every endpoint requires an
  authenticated caller unless it opts out with `AllowAnonymous`; role
  endpoints declare the single role they need (`moderator`/`admin`) and the
  admin ⊃ moderator hierarchy stays in Keycloak.
- **WebApplicationFactory integration tests without containers** — the suite
  swaps in EF Core's in-memory provider and a test auth handler, so endpoint
  routing, auth policies, services, and EF-tracked state machine behavior are
  exercised in process while the canonical live-DB conformance suite remains
  the PostgreSQL acceptance gate.
- **Enforced Roslyn and formatting baseline** — .NET 10's recommended analyzers,
  build-time code-style checks, and warnings-as-errors apply solution-wide;
  `dotnet format --verify-no-changes` is a separate CI gate.
- **Body-hash ETags in middleware** — `EtagMiddleware` buffers message/stats
  responses and derives the ETag from the bytes, the same stateless
  revalidation scheme as the reference backend's `ShallowEtagHeaderFilter`.
- **Observability** (docs/RUNNING.md) — the OpenTelemetry .NET SDK, wired in
  code (ASP.NET Core + HttpClient + Npgsql instrumentation, OTLP for
  traces/metrics/logs), active only when `OTEL_SDK_DISABLED=false`.

## Deliberate deviations worth comparing

- Enum wire values (`public`, `broken-link`, ...) come from one kebab-case
  `JsonNamingPolicy` applied globally, and the same strings are stored in the
  database — where spring-kotlin stores uppercase constant names and maps per
  enum with `@JsonValue`.
- `GET /api/v1/admin/stats` assembles its totals with several small queries
  (as the reference does) but the daily series uses two grouped raw-SQL
  queries; EF Core has no native `date_trunc` grouping over `timestamptz` at
  UTC.
- Validation is hand-rolled (`Common/Problems.cs`), not DataAnnotations:
  the contract wants all field errors collected in one RFC 9457 problem with
  localized messages, which is simpler to do directly than to bend the
  framework validators.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (.NET SDK) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ✅¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted — with `duration_ms` measured at the
failing call — for both of this backend's dependencies: PostgreSQL (EF Core
command, connection, and transaction interceptors, which also cover the
readiness probe) and the Keycloak metadata/JWKS fetch (an instrumented
document retriever). There are no retry loops, so `retry_exhausted` has no
occurrence to log.
