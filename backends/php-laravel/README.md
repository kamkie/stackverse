# Backend · PHP Laravel

The Stackverse backend in PHP 8.3+ using **Laravel 13**, Laravel routing,
middleware, migrations, the service container, and raw PostgreSQL queries for
the contract logic. Shared behavior, endpoints, and environment variables are
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
  middleware validates bearer tokens, controllers stay thin, and Laravel
  sessions/cache are in-memory because the gateway owns browser session state.
- **Laravel migrations over a PostgreSQL-owned schema** — one migration creates
  the Stackverse tables, including `text[]` tags, partial unique indexes for
  reports, and GIN indexing.
- **Raw SQL through Laravel's database layer** — feature behavior stays close
  to the contract: row locks for moderation, stable keyset pagination, and
  body-hash ETags with no process-local cache state.
- **JWKS validation without browser tokens** — the middleware validates
  `iss`, `aud`, expiry, and RS256 signatures against `OIDC_JWKS_URI` or OIDC
  discovery, then derives identity from `preferred_username`.
- **FrankenPHP image** — the Dockerfile installs only production dependencies,
  runs as a non-root user, executes startup tasks, and serves the app on
  `PORT`.

## Deliberate Deviations Worth Comparing

- Eloquent models are intentionally not used for the domain. Laravel's query
  builder/DB layer keeps the SQL visible for a contract-first comparison,
  especially PostgreSQL arrays, partial indexes, and row-locking behavior.
- Laravel Sanctum, Passport, guards, and server-side sessions are not used.
  Stackverse backends are stateless bearer-token resource servers; the gateway
  owns the browser session.
- Response DTOs are hand-built arrays rather than Eloquent resources so
  optional fields can be omitted rather than serialized as `null`.

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
