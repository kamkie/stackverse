# Gateway: OpenResty (nginx + Lua)

The Stackverse BFF as an infrastructure-as-gateway variant: OpenResty serves
nginx configuration plus Lua handlers, using `lua-resty-openidc` for the OIDC
authorization-code flow and `lua-resty-session` with Redis storage for sessions.
Route contract, cookie rules, and the login sequence live in
[docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md); shared env vars in
[gateways/README.md](../README.md).

## How it maps to the contract

| Contract | Here |
|---|---|
| `GET /auth/login` | Lua handler calls `lua-resty-openidc.authenticate` with PKCE enabled |
| `GET /auth/callback` | same OpenID Connect handler consumes the callback, validates the ID token, and redirects to `/` |
| `POST /auth/logout` | local `lua-resty-session` destruction first, best-effort server-to-server Keycloak logout, `204` |
| `GET /auth/session` | reads the Redis-backed session behind `stackverse_session` |
| `/api/**` | access-phase Lua applies session/refresh/CSRF policy, then native `proxy_pass` streams to `BACKEND_URL` with browser credentials stripped and a session bearer token attached when present |
| `/**` | native `proxy_pass` streams to `FRONTEND_URL` when set; otherwise Lua serves `SPA_ROOT` or the bundled placeholder |

## Design notes

- **Stateless process.** `stackverse_session` is the `lua-resty-session` cookie
  name. With `storage = "redis"`, the browser cookie carries the encrypted
  session header / id while the OIDC state and token-bearing session data live
  in Redis. The gateway process can be replicated without affinity.
- **OIDC code flow with PKCE.** Browser redirects use `OIDC_ISSUER_URI`.
  Server-side token, JWKS, and logout endpoints are based on
  `OIDC_INTERNAL_ISSUER_URI` when set, while ID token issuer validation still
  uses the public issuer.
- **Refresh failure split.** The gateway refreshes access tokens explicitly
  rather than letting `lua-resty-openidc` redirect on expiry. Keycloak `400` or
  `401` responses destroy the local session and degrade the original API
  request to anonymous. Unreachable Keycloak, `5xx`, `429`, other non-success
  statuses, or malformed token responses keep the session and return `503`.
- **Anonymous `/api/**` relay.** Public API operations work logged out. The
  backend owns authorization; the gateway only attaches a token when one is
  available. Client-supplied `Authorization`, `Cookie`, and `X-XSRF-TOKEN`
  headers are stripped before proxying.
- **Native streaming data plane.** Lua runs in the access phase to establish
  sanitized upstream URL, host, authorization, and trace variables. nginx's
  `proxy_pass` sends request bodies and responses with buffering disabled, so
  application Lua never reads, stores, or re-emits normal proxied bodies.
  Native proxy handling retains nginx backpressure and hop-by-hop behavior;
  upstream TLS uses SNI plus system-CA certificate verification. nginx-generated
  upstream failures enter small Lua error handlers so API failures remain RFC
  9457 problem documents.
- **CSRF and same-origin boundary.** State-changing `/api/**` requests require
  the readable `XSRF-TOKEN` cookie to match `X-XSRF-TOKEN`, and browser
  `Origin` / `Sec-Fetch-Site` signals must match `PUBLIC_URL`.
- **Security headers.** SPA and `/auth/**` responses receive the full browser
  hardening header set. Proxied `/api/**` responses receive only
  `X-Content-Type-Options: nosniff` plus HTTPS-only HSTS, leaving backend cache,
  ETag, `304`, `Content-Language`, and body semantics untouched.
- **Observability.** Without a native OpenTelemetry SDK in OpenResty, this
  variant implements the required edge behavior directly: W3C `traceparent` is
  preserved or generated for proxied `/api/**` requests when
  `OTEL_SDK_DISABLED=false`, and structured gateway events are exported over
  OTLP/HTTP JSON to `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` or to
  `OTEL_EXPORTER_OTLP_ENDPOINT` with `/v1/logs` appended. The compose default
  `:4317` endpoint is translated to `:4318` for this HTTP exporter.
- **Logging** writes one structured JSON event per line by default, with
  `LOG_FORMAT=text` available for local development. The Lua logger emits the
  contract event names for lifecycle, callback, session, refresh, CSRF, logout,
  and dependency failures, and never logs tokens, cookies, or the client
  secret.

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
docker build -t stackverse/gateway-openresty:local gateways/openresty
GATEWAY_IMAGE=stackverse/gateway-openresty:local docker compose --profile app up gateway
# -> http://localhost:8000, log in as demo/demo
```

## Test

The component check builds the image, runs `luacheck`, validates the rendered
OpenResty config, curls through live gateway/upstream containers to exercise
native forwarding and header policy, runs Busted specs for the proxy
configuration, and runs the Lua contract smoke suite with coverage inside the
image.

```sh
docker build -t stackverse/gateway-openresty:local .
docker run --rm stackverse/gateway-openresty:local luacheck /opt/stackverse/lua --config /opt/stackverse/.luacheckrc
docker run --rm stackverse/gateway-openresty:local openresty -t -c /usr/local/openresty/nginx/conf/nginx.conf
docker run --rm stackverse/gateway-openresty:local busted /opt/stackverse/test/native_proxy_spec.lua
mkdir -p coverage
docker run --rm -v "$(pwd)/coverage:/coverage" stackverse/gateway-openresty:local \
  resty -I /opt/stackverse/lua /opt/stackverse/test/coverage.lua \
  /opt/stackverse/test/smoke.lua /coverage/lcov.info
```

The focused Busted suite verifies that gateway policy stays in the access
phase, normal upstream traffic uses native `proxy_pass`, request/response
buffering is disabled, upstream TLS is verified, and browser credentials are
stripped. The live container check verifies request-body forwarding, selective
request/response headers, the bare `/api` route, trace propagation, and frontend
upgrade handling. The broader smoke harness keeps the contract decision matrix
fast and deterministic with mocked Redis, OIDC, and nginx request state. It
also covers URL/session configuration, Redis readiness classification, and
OTLP log shaping, trace correlation, sanitization, and secret exclusion. On
Windows, use an absolute host path for the coverage bind mount.

## Docker

```sh
docker build -t stackverse/gateway-openresty:local .
# then from the repo root:
# GATEWAY_IMAGE=stackverse/gateway-openresty:local docker compose --profile app up
```
