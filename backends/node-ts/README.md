# Backend · Node.js (TypeScript)

The Stackverse backend on Node.js 24: **Fastify 5** over plain `pg`, JWT bearer
authentication with `jose` against Keycloak's JWKS, `pino` structured logging.
Shared behavior, endpoints, and environment variables are documented once in
[backends/README.md](../README.md) and the contract documents it points to —
this file covers only what is specific to this stack.

Framework choice: Fastify over Express for first-class async handlers, a real
plugin/hook model (the auth hook, the problem-document error handler), and a
pino logger built in — the idiomatic 2020s Node service shape. The rest of the
stack is deliberately thin: no ORM, no DI container, no validation framework.

## Run it locally

Prerequisites: Node.js ≥ 22 with corepack (Yarn Berry resolves from
`packageManager`), the compose infra (`docker compose up -d` at the repo root).

```sh
cd backends/node-ts
yarn install
yarn build
yarn start          # or: yarn dev (rebuild once, restart on dist changes)
```

Defaults match the compose infra (PostgreSQL on 5432, Keycloak on 8180); the
message seed resolves to the repo's `spec/messages` automatically. Migrations
(node-pg-migrate) apply on startup — the database must be one this backend owns
(when switching from another backend: `docker compose down -v` first, see
[docs/RUNNING.md](../../docs/RUNNING.md)).

Tests (Vitest units plus Fastify injection, no containers):

```sh
yarn lint           # ESLint flat config: JavaScript + TypeScript recommended rules
yarn format:check   # Prettier verification (use yarn format to rewrite)
yarn typecheck      # tsc --noEmit
yarn test --coverage   # Vitest + LCOV under coverage/
```

The suite drives the real Fastify request boundary for authentication,
messages, moderation, ETags, and RFC 9457 errors while replacing PostgreSQL,
OIDC discovery, and other dependencies with deterministic fakes. It is not a
live-database integration suite; shared conformance remains that acceptance
boundary.

Conformance (the acceptance gate), with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context — the image ships the message seed),
from the repo root:

```sh
docker build -t stackverse/backend-node-ts:local -f backends/node-ts/Dockerfile .
```

## What this implementation demonstrates

- **Fastify hooks as the security boundary** — one `onRequest` hook does JWT
  validation, lazy account provisioning, and the blocked-account 403
  (`src/auth.ts`); route handlers ask for what they need with
  `requireCaller`/`requireRole`. The admin ⊃ moderator hierarchy stays in
  Keycloak.
- **SQL without an ORM** — hand-written parameterized queries per feature
  module (`src/routes/*.ts`), with a tiny `withTransaction` helper for the
  moderation state machine. What an ORM would hide — lock ordering, keyset
  predicates, partial unique indexes — is exactly the comparison material.
- **PostgreSQL arrays for tags** — `tags text[]` with a GIN index, as in the
  dotnet sibling; tag filters are array containment (`@>`), tag counts go
  through `unnest`. Contrast with spring-kotlin's join table.
- **Keyset pagination in plain SQL** — the v2 cursor is the same base64url
  `(created_at, id)` token as the reference backend, appended to the listing
  `WHERE` clause as one keyset predicate (`src/cursor.ts`).
- **Body-hash ETags** — `sendWithEtag` (`src/etag.ts`) hashes the serialized
  response, the same stateless revalidation scheme as the reference backend's
  `ShallowEtagHeaderFilter`; bundle and stats bodies are deterministically
  ordered so identical reads produce identical tags.
- **Yarn Berry PnP, ESM, tsc, ESLint, and Prettier** — zero `node_modules`,
  matching the repo's other Node projects; strict type-checking, linting, and
  formatting are separate local and CI gates. The Docker runtime stage runs
  plain `node` with the PnP require hook + ESM loader, no Yarn in the image.
- **Observability** (docs/RUNNING.md) — the OpenTelemetry NodeSDK, wired in
  code (`src/otel.ts`: HTTP + pg instrumentation, OTLP/gRPC for
  traces/metrics/logs) and active only when `OTEL_SDK_DISABLED=false`. Console
  lines pick up `trace_id`/`span_id` through a pino mixin reading the active
  span, and a pino stream bridges every record to the OTel Logs API — no
  monkey-patching of the logger.

## Deliberate deviations worth comparing

- Enum-like values are stored as their lowercase wire strings (`'public'`,
  `'open'`) — there is no enum layer to map through, so the database reads
  like the API. spring-kotlin stores uppercase constant names; dotnet derives
  the same lowercase strings from a naming policy.
- Optional response fields are *omitted*, not `null` — a small helper
  (`omitNulls`) instead of a serializer-wide `NON_NULL` setting.
- A malformed UUID in a path answers `404` (a malformed id cannot name an
  existing resource), where Spring's type conversion answers `400`. The
  contract only pins the valid-UUID cases; this is the kind of edge that
  differs between stacks unless someone pins it.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (pino → OTel Logs API bridge) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ (pino mixin) |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ✅¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ (`text` → pino-pretty) |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for both of this backend's dependencies:
PostgreSQL (pool-level connection errors and the readiness probe's
ready→not-ready transition, with `duration_ms`) and Keycloak (OIDC discovery /
JWKS retrieval failure). There are no retry loops, so `retry_exhausted` has no
occurrence to log.
