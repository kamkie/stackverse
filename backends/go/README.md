# Backend · Go (stdlib + chi)

The Stackverse backend in Go 1.26: `net/http` with the [chi](https://github.com/go-chi/chi)
router, [pgx](https://github.com/jackc/pgx) against PostgreSQL, JWT bearer
authentication against Keycloak's JWKS. Shared behavior, endpoints, and
environment variables are documented once in [backends/README.md](../README.md)
and the contract documents it points to — this file covers only what is
specific to this stack.

## Run it locally

Prerequisites: Go 1.26, the compose infra
(`docker compose up -d` at the repo root).

```sh
cd backends/go
go run ./cmd/backend
```

Defaults match the compose infra (PostgreSQL on 5432, Keycloak on 8180); the
message seed resolves to the repo's `spec/messages` relative to this
directory — override with `SEED_MESSAGES_DIR` when running from anywhere else.
Migrations apply on startup — the database must be one this backend owns
(when switching from another backend: `docker compose down -v` first, see
[docs/RUNNING.md](../../docs/RUNNING.md)).

Local verification (plain unit tests, no containers; `gofmt -l .` should
print no files):

```sh
go mod verify
gofmt -l .
go build ./...
go vet ./...
go test ./...
```

Conformance (the acceptance gate), with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context — the image ships the message seed),
from the repo root:

```sh
docker build -t stackverse/backend-go:local -f backends/go/Dockerfile .
```

## What this implementation demonstrates

- **Minimal dependencies, maximal stdlib** — chi for routing, pgx for
  PostgreSQL, `golang-jwt` for token parsing, and the OpenTelemetry SDK; no
  web framework, no ORM. Handlers are plain `http.HandlerFunc`s over raw SQL.
- **Package by feature** — `bookmarks/`, `messages/`, `moderation/`,
  `accounts/`, `audit/`, `stats/` under `internal/`; each owns its SQL,
  DTOs, and handlers. Cross-cutting HTTP plumbing (problems, paging, ETag,
  deprecation headers) lives once in `internal/web`.
- **Hand-rolled migrations** — an embedded SQL directory applied at startup
  under a PostgreSQL advisory lock (`internal/store`), ~80 lines instead of a
  migration framework; each applied file logs `db_migration_applied`.
- **Hand-rolled JWKS** — `internal/auth` fetches the key set (from
  `OIDC_JWKS_URI` or OIDC discovery) with stdlib crypto, caches it, and
  refreshes rate-limited on unknown key ids; `iss`/`aud`/`exp` are validated
  by `golang-jwt`. Lazy on first request, so the service comes up while
  Keycloak is still booting.
- **PostgreSQL arrays for tags** — `tags text[]` with a GIN index (as the
  .NET sibling does); repeated tag filters become one array-containment
  check (`tags @> $1` = AND semantics), tag counts go through `unnest`.
- **Keyset pagination as a row comparison** — the v2 cursor listing walks
  `(created_at, id)` with PostgreSQL's native row-constructor comparison
  `(created_at, id) < ($1, $2)`, the closest SQL gets to expressing a keyset
  predicate directly.
- **Body-hash ETags in middleware** — `web.ETagMiddleware` buffers
  message/stats responses and derives the ETag from the bytes, the same
  stateless revalidation scheme as the reference backend.
- **Transactions with explicit lock ordering** — report resolution locks the
  bookmark row before any report rows (`moderation.Resolve`), serializing
  concurrent `actioned` resolutions per bookmark instead of deadlocking;
  diagnostic log events are queued and emitted only after commit.
- **Observability** (docs/RUNNING.md) — the OpenTelemetry Go SDK wired in
  code: `otelhttp` server spans, OTLP traces/metrics/logs, and a
  `log/slog` bridge so every log record ships with its trace context;
  active only when `OTEL_SDK_DISABLED=false`.

## Deliberate deviations worth comparing

- Enum wire values (`public`, `broken-link`, ...) are stored in the database
  as-is — plain `text` columns with the contract's lowercase strings, no
  mapping layer at all (spring-kotlin stores uppercase constant names;
  dotnet derives the same lowercase strings from a naming policy).
- Timestamps are truncated to microseconds at creation (`store.NowUTC`) so
  the in-memory value a create response serializes is byte-identical to what
  later reads scan back from `timestamptz` — Go's RFC 3339 formatting would
  otherwise disagree with PostgreSQL about nanoseconds.
- Validation is hand-rolled (`web.Validator`), not a validation library: the
  contract wants all field errors collected into one RFC 9457 problem with
  messages localized from the database, which is simpler to express directly.
- "Unresolvable cursor → 400" is interpreted as *undecodable*, exactly as the
  reference backend documents it: a well-formed but never-issued cursor is
  indistinguishable, on a stateless service, from a cursor whose boundary row
  was deleted between pages — and that one must keep working.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (Go SDK, `otelslog` bridge) |
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

¹ `dependency_call_failed` is emitted for both of this backend's
dependencies: PostgreSQL (a `pgx` query tracer with `duration_ms`, skipping
integrity-constraint violations — those surface as expected 409s) and the
Keycloak discovery/JWKS fetch. There are no retry loops, so
`retry_exhausted` has no occurrence to log.
