# Backend - Node.js (NestJS)

The Stackverse backend on Node.js 24: **NestJS 11** on the Fastify adapter,
plain `pg` for PostgreSQL access, JWT bearer authentication with `jose`
against Keycloak's JWKS, and `pino` structured logging. Shared behavior,
endpoints, and environment variables are documented once in
[backends/README.md](../README.md) and the contract documents it points to -
this file covers only what is specific to this stack.

Framework choice: NestJS for the opinionated application shell, module
lifecycle, controller/provider model, guards, filters, and adapter boundary
that many TypeScript teams use in production. The Stackverse contract routes
are expressed as Nest feature modules with controllers and injectable services;
Fastify remains only the HTTP adapter.

## Run it locally

Prerequisites: Node.js >= 22 with corepack (Yarn Berry resolves from
`packageManager`), the compose infra (`docker compose up -d` at the repo root).

```sh
cd backends/node-nestjs
yarn install
yarn build
yarn start          # or: yarn dev / yarn start:dev (Nest watch mode)
```

Defaults match the compose infra (PostgreSQL on 5432, Keycloak on 8180); the
message seed resolves to the repo's `spec/messages` automatically. Migrations
(node-pg-migrate) apply on startup - the database must be one this backend owns
(when switching from another backend: `docker compose down -v` first, see
[docs/RUNNING.md](../../docs/RUNNING.md)).

Tests (plain unit tests, no containers):

```sh
yarn lint
yarn typecheck      # tsc --noEmit
yarn test           # vitest
yarn format:check
```

Conformance (the acceptance gate), with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context - the image ships the message seed),
from the repo root:

```sh
docker build -t stackverse/backend-node-nestjs:local -f backends/node-nestjs/Dockerfile .
```

## What this implementation demonstrates

- **NestJS modules, controllers, and services** - `src/app.module.ts` imports
  feature modules (`bookmarks`, `messages`, `moderation`, `admin-users`,
  `audit-log`, `stats`, `meta`); controllers own the HTTP decorators and
  injectable services own the contract logic.
- **Nest guard + exception filter** - a global `BearerAuthGuard` validates JWTs,
  lazily provisions accounts, and enforces blocked-account rejection
  (`src/auth.ts`), while `ProblemFilter` is the single RFC 9457 renderer for
  validation, authorization, and unexpected errors.
- **Nest CLI build/dev flow** - `nest-cli.json` points at the `server.ts`
  entrypoint; `yarn build` and `yarn dev` run through the Nest CLI.
- **ESLint + Prettier scaffold scripts** - this variant has package-local
  lint and format scripts, matching a conventional Nest TypeScript project.
- **Fastify adapter boundary** - Fastify is still the Nest platform adapter so
  the backend keeps pino integration and Fastify replies for explicit status,
  header, and ETag handling, but routes are not registered directly on a
  Fastify instance.
- **SQL without an ORM** - hand-written parameterized queries per feature
  service (`src/*/*.service.ts`), with a tiny `withTransaction` helper for the
  moderation state machine. Lock ordering, keyset predicates, and partial
  unique indexes remain visible comparison material.
- **PostgreSQL arrays for tags** - `tags text[]` with a GIN index; tag filters
  are array containment (`@>`), tag counts go through `unnest`.
- **Keyset pagination in plain SQL** - the v2 cursor is a base64url
  `(created_at, id)` token appended to the listing `WHERE` clause as one
  keyset predicate (`src/cursor.ts`).
- **Body-hash ETags** - `sendWithEtag` (`src/etag.ts`) hashes the serialized
  response, a stateless revalidation scheme with deterministic message bundle
  and stats bodies.
- **Yarn Berry PnP, ESM, Nest CLI, and tsc** - zero `node_modules`, matching
  the repo's other Node projects; the Docker runtime stage runs plain `node`
  with the PnP require hook + ESM loader, no Yarn in the image.
- **Observability** (docs/RUNNING.md) - the OpenTelemetry NodeSDK, wired in
  code (`src/otel.ts`: HTTP + pg instrumentation, OTLP/gRPC for
  traces/metrics/logs) and active only when `OTEL_SDK_DISABLED=false`. Console
  lines pick up `trace_id`/`span_id` through a pino mixin reading the active
  span, and a pino stream bridges every record to the OTel Logs API.

## Deliberate deviations worth comparing

- This backend deliberately avoids TypeORM/Prisma. Nest owns the module,
  controller, provider, guard, and filter layers while persistence stays
  explicit, so the TypeScript comparison can separate framework structure from
  ORM structure.
- Validation stays in a hand-rolled `Validator` rather than class-validator DTO
  decorators. The Stackverse contract requires localized field errors with
  runtime-managed message keys, and keeping that accumulator explicit makes the
  cross-stack comparison easier to read.
- Enum-like values are stored as their lowercase wire strings (`'public'`,
  `'open'`) - there is no enum layer to map through, so the database reads
  like the API.
- Optional response fields are omitted, not `null` - a small helper
  (`omitNulls`) instead of a serializer-wide setting.
- A malformed UUID in a path answers `404` (a malformed id cannot name an
  existing resource), where some stacks answer `400`. The contract only pins
  the valid-UUID cases.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (pino -> OTel Logs API bridge) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ (pino mixin) |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ✅¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ (`text` -> pino-pretty) |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for both of this backend's dependencies:
PostgreSQL (pool-level connection errors and the readiness probe's
ready-to-not-ready transition, with `duration_ms`) and Keycloak (OIDC
discovery / JWKS retrieval failure). There are no retry loops, so
`retry_exhausted` has no occurrence to log.
