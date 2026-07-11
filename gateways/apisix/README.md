# Gateway: Apache APISIX

The Stackverse BFF as an Apache APISIX variant. APISIX runs in standalone
YAML mode and routes all gateway traffic through a small custom APISIX plugin;
the Stackverse-specific handlers use the same OpenResty ecosystem APISIX is
built on (`lua-resty-openidc`, `lua-resty-session`, Redis storage, and
cosocket HTTP clients). Route contract, cookie rules, and the login sequence
live in [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md); shared env vars in
[gateways/README.md](../README.md).

## How it maps to the contract

| Contract | Here |
|---|---|
| `GET /auth/login` | custom APISIX plugin calls `lua-resty-openidc.authenticate` with PKCE enabled |
| `GET /auth/callback` | same handler consumes the callback, validates the ID token, and redirects to `/` |
| `POST /auth/logout` | local `lua-resty-session` destruction first, best-effort server-to-server Keycloak logout, `204` |
| `GET /auth/session` | reads the Redis-backed session behind `stackverse_session` |
| `/api/**` | custom plugin relays to `BACKEND_URL`, stripping browser credentials and attaching a bearer token when the session has one |
| `/**` | proxies `FRONTEND_URL` when set; otherwise serves `SPA_ROOT` or the bundled placeholder |

## Design notes

- **Standalone APISIX.** `conf/config.yaml` disables the Admin API and etcd;
  `conf/apisix.yaml` defines one catch-all APISIX route with the
  `stackverse-gateway` plugin. The APISIX route/plugin lifecycle owns traffic
  entry; the plugin owns the contract-specific BFF behavior.
- **Stateless process.** `stackverse_session` is the `lua-resty-session` cookie
  name. With `storage = "redis"`, OIDC state and token-bearing session data
  live in Redis, so APISIX workers can be replicated without affinity.
- **OIDC code flow with PKCE.** Browser redirects use `OIDC_ISSUER_URI`.
  Server-side token, JWKS, and logout endpoints are based on
  `OIDC_INTERNAL_ISSUER_URI` when set, while ID token issuer validation still
  uses the public issuer.
- **Refresh failure split.** Keycloak `400` or `401` refresh responses destroy
  the local session and degrade the original API request to anonymous.
  Unreachable Keycloak, `5xx`, `429`, other non-success statuses, or malformed
  token responses keep the session and return `503`.
- **Anonymous `/api/**` relay.** Public API operations work logged out. The
  backend owns authorization; the gateway only attaches a token when one is
  available. Client-supplied `Authorization`, `Cookie`, and `X-XSRF-TOKEN`
  headers are stripped before proxying.
- **CSRF and same-origin boundary.** State-changing `/api/**` requests require
  the readable `XSRF-TOKEN` cookie to match `X-XSRF-TOKEN`, and browser
  `Origin` / `Sec-Fetch-Site` signals must match `PUBLIC_URL`.
- **Security headers.** SPA and `/auth/**` responses receive the full browser
  hardening header set. Proxied `/api/**` responses receive only
  `X-Content-Type-Options: nosniff` plus HTTPS-only HSTS, leaving backend cache,
  ETag, `304`, `Content-Language`, and body semantics untouched.
- **Observability.** APISIX does not expose a turnkey Stackverse trace/log
  contract for custom BFF flows, so this variant emits structured gateway
  events and OTLP/HTTP logs from Lua, and preserves or creates W3C
  `traceparent` for proxied `/api/**` requests when
  `OTEL_SDK_DISABLED=false`.

## Deliberate deviations worth comparing

- APISIX's `openid-connect` plugin is not used for the main flow. It protects
  upstream routes, but Stackverse needs exact `/auth/*` routes, anonymous API
  relay, and refresh rejection vs. IdP outage behavior. The variant still uses
  the same `lua-resty-openidc` foundation inside a custom APISIX plugin.
- APISIX's CSRF and proxy plugins are not used for `/api/**`. The contract
  requires double-submit plus `Origin` / `Sec-Fetch-Site` validation with a
  specific problem response, and token refresh can change whether a request is
  relayed with a bearer token or anonymously.
- Tests use the same small Lua harness style as the OpenResty sibling instead
  of Test::Nginx or busted, keeping the component check inside the APISIX image
  with no extra test runner.
- No separate Lua linter or formatter is configured; CI builds the image,
  validates APISIX config, and runs smoke tests.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (OTLP/HTTP JSON logs) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ❌ gap¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for Redis, Keycloak refresh/logout, and
upstream proxy failures. `retry_exhausted` is not emitted because the gateway
does not implement retries.

## Configuration

All shared variables from [gateways/README.md](../README.md). `FRONTEND_URL`
takes precedence over local static serving. When it is unset, `SPA_ROOT`
points at a production SPA build; if `SPA_ROOT` is also unset, the image serves
a small bundled placeholder page.

## Run

```sh
# infra first, from the repo root: docker compose up -d
docker build -t stackverse/gateway-apisix:local gateways/apisix
GATEWAY_IMAGE=stackverse/gateway-apisix:local docker compose --profile app up gateway
# -> http://localhost:8000, log in as demo/demo
```

## Test

The component check builds the image, validates the APISIX config, and runs the
Lua harness inside the image. The deterministic `ngx`/module-state harness
covers configuration, CSRF and route dispatch, Redis readiness, proxy/header
policy, and logging/OTLP privacy boundaries; it does not replace a live
Redis/Keycloak integration test.

```sh
docker build -t stackverse/gateway-apisix:local .
docker run --rm stackverse/gateway-apisix:local apisix test
mkdir -p coverage
chmod 777 coverage
docker run --rm -v "$(pwd)/coverage:/coverage" stackverse/gateway-apisix:local \
  resty -I /opt/stackverse/lua -I /usr/local/apisix/deps/share/lua/5.1 \
  /opt/stackverse/test/coverage.lua /opt/stackverse/test/smoke.lua /coverage/lcov.info
```

On Windows, use an absolute host path for the bind mount. The report is written
to `coverage/lcov.info`.

## Docker

```sh
docker build -t stackverse/gateway-apisix:local .
# then from the repo root:
# GATEWAY_IMAGE=stackverse/gateway-apisix:local docker compose --profile app up
```
