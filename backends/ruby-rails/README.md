# Backend · Ruby on Rails API

The Stackverse backend in Ruby 3.4 and Rails 7.2 API mode: Rails routing and
controllers, ActiveRecord migrations/models for PostgreSQL, JWT bearer
authentication against Keycloak's JWKS, and structured stdout logging. Shared
behavior, endpoints, and environment variables are documented once in
[backends/README.md](../README.md) and the contract documents it points to.

## Run it locally

Prerequisites: Ruby 3.4, Bundler, and the compose infra (`docker compose up -d`
at the repo root).

```sh
cd backends/ruby-rails
bundle install
bundle exec puma -C config/puma.rb
```

Defaults match the compose infra (PostgreSQL on 5432, Keycloak on 8180). The
Rack startup path applies ActiveRecord migrations and idempotently imports
`spec/messages/*.json`; when switching from another backend, wipe the compose
database volume first (`docker compose down -v`).

Tests:

```sh
bundle exec rails zeitwerk:check
bundle exec rails test
```

Conformance (the acceptance gate), with the backend running:

```sh
../../scripts/conformance.ps1        # or conformance.sh
```

Container image (repo root as context, so the image ships the message seed):

```sh
docker build -t stackverse/backend-ruby-rails:local -f backends/ruby-rails/Dockerfile .
```

## What this implementation demonstrates

- **Rails API mode as a thin HTTP layer** — Rails routes/controllers own request
  dispatch and problem rendering, while contract-heavy behavior sits in small
  service objects under `app/services/stackverse`.
- **ActiveRecord-owned schema** — Rails migrations create the backend's private
  PostgreSQL schema, including `text[]` tags, partial unique indexes, and the
  append-only audit table.
- **Explicit SQL for contract-sensitive flows** — keyset pagination,
  moderation row locks, partial-index conflict checks, and stats aggregation stay
  close to PostgreSQL so behavior matches the sibling backends exactly.
- **Stateless body-hash ETags** — message reads and stats hash the serialized
  response body, so writes invalidate validators without process-local cache.
- **OIDC/JWKS validation at the backend** — `OIDC_JWKS_URI` can point at
  compose's internal Keycloak address while `iss` still validates against
  `OIDC_ISSUER_URI`.

## Deliberate deviations worth comparing

- The implementation uses Rails controllers plus service objects rather than
  pushing all behavior into fat ActiveRecord models. The moderation state
  machine and report sibling resolution are easier to compare across backends
  when the transaction boundaries and SQL locks stay visible.
- Request validation is programmatic instead of ActiveModel validations because
  the API must return localized RFC 9457 field errors with contract-defined
  `validation.*` message keys.
- OpenTelemetry trace/log export is not wired yet; structured stdout logs follow
  the required event vocabulary, but OTLP export remains a visible backlog item.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ❌ gap — OpenTelemetry export not wired yet |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ❌ gap — tracing not wired yet |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ✅¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for PostgreSQL readiness/request failures
and Keycloak discovery/JWKS failures. There are no retry loops, so
`retry_exhausted` has no occurrence to log.
