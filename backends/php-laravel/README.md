# Backend · PHP Laravel

The Stackverse backend in PHP 8.3+ using **Laravel 13**, Laravel routing,
middleware, migrations, the service container, and raw PostgreSQL queries for
the few PostgreSQL-specific contract operations. Shared behavior, endpoints, and environment variables are
documented once in [backends/README.md](../README.md) and the contract
documents it points to.

## Run It Locally

Prerequisites: PHP 8.3+ with `pdo_pgsql`, Composer, and the compose infra
(`docker compose up -d` at the repo root).

The lockfile is resolved with Composer's `config.platform.php` set to PHP
8.3.0, so contributors on newer local PHP versions keep dependency updates
compatible with the documented PHP 8.3+ baseline.

```sh
cd backends/php-laravel
composer install
php artisan stackverse:serve
```

Defaults match the compose infra: PostgreSQL on `localhost:5432`, Keycloak on
`localhost:8180`, and the backend on `PORT` 8080. `stackverse:serve` runs
startup migrations and idempotent message seed import before starting Laravel's
local server.

Tests:

```sh
composer validate --strict --no-check-publish
composer lint
php artisan test
```

The model/resource integration test is opt-in so the default unit/HTTP suite
does not require infrastructure. Against a migrated PostgreSQL test database,
set `STACKVERSE_DB_TESTS=true` and run
`php artisan test --filter EloquentBoundariesTest`.

Conformance, with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context because the image ships `spec/messages`):

```sh
docker build -t stackverse/backend-php-laravel:local -f backends/php-laravel/Dockerfile .
```

## What This Implementation Demonstrates

- **Laravel as an API-only resource server** — routes live in `routes/api.php`,
  the `api` guard resolves bearer callers, route middleware applies caller/role
  policy before controllers, and Laravel sessions remain unused because the
  gateway owns browser session state. The optional bearer middleware is placed
  ahead of Laravel's authentication priority marker so verification failures
  are normalized before protected-route checks, and guest redirects are
  disabled so missing credentials remain API `401` responses rather than
  browser redirects.
- **FormRequest validation and API resources** — concrete request classes own
  structural rules, normalization, and exact localized contract keys; Eloquent
  models plus unwrapped `JsonResource` classes own normal persistence and wire
  serialization boundaries. HTTP feature tests exercise router, middleware,
  FormRequest, exception, and resource behavior together.
- **Laravel migrations over a PostgreSQL-owned schema** — one migration creates
  the Stackverse tables, including `text[]` tags, partial unique indexes for
  reports, and GIN indexing.
- **Eloquent plus focused PostgreSQL SQL** — models/query builders handle normal
  CRUD, filters, pagination, and row locks. Raw SQL remains only where
  PostgreSQL arrays, tuple keysets, aggregates, or readiness probes make the
  database-specific operation clearer. The shared application `BaseModel`
  serializes timestamps with microseconds and the PostgreSQL connection runs in
  UTC, preserving instants and ordering for pagination, cursors, and
  time-filtered audit queries.
- **Maintained JOSE/JWT boundary** — Laravel's request guard delegates JWK and
  RS256 verification to `firebase/php-jwt`, validates `iss`/`aud`, and derives
  identity from `preferred_username`; no application-owned cryptography remains.
- **FrankenPHP image** — the Dockerfile installs only production dependencies,
  runs as a non-root user, executes startup tasks, and serves the app on
  `PORT`.

## Deliberate Deviations Worth Comparing

- Sanctum/Passport and server-side sessions remain intentionally absent:
  Stackverse is a stateless bearer-token resource server. A small Laravel
  request guard is the appropriate integration point for externally issued
  Keycloak JWTs, while the gateway owns browser sessions.
- PostgreSQL `text[]`, GIN/tag aggregation, tuple keysets, and daily aggregates
  retain focused SQL fragments because those operations are deliberately part
  of the cross-stack database comparison.

## Logging Conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ❌ gap: console logs are structured, but no OTLP log exporter is wired yet |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ❌ gap: no OpenTelemetry tracing integration yet |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ✅¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for PostgreSQL readiness failures and
Keycloak discovery/JWKS failures. There are no retry loops, so
`retry_exhausted` has no occurrence to log.
