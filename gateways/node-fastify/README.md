# Gateway: Node.js Fastify

The Stackverse BFF on Node.js 26 / TypeScript with [Fastify](https://fastify.dev/).
Route contract, cookie rules, and the login sequence live in
[docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md); shared env vars in
[gateways/README.md](../README.md).

## How it maps to the contract

| Contract | Here |
|---|---|
| `GET /auth/login` | route building the OIDC authorization request (code flow + PKCE S256) |
| `GET /auth/callback` | route exchanging the code, verifying the ID token, storing a Redis session, and redirecting to `/` |
| `POST /auth/logout` | route: local Redis session destroyed first, best-effort server-to-server Keycloak logout, `204` |
| `GET /auth/session` | route reading the Redis session keyed by `stackverse_session` |
| `/api/**` | Fastify catch-all proxy to `BACKEND_URL` with bearer-token attachment |
| `/**` | proxy to `FRONTEND_URL` when set; otherwise static files from `SPA_ROOT` or the bundled placeholder |

## Design notes

- **Stateless process.** The `stackverse_session` cookie carries only an opaque
  random id; tokens and username live in Redis under `stackverse:session:*`.
  OIDC state and PKCE verifier data also live in Redis, so callback handling
  needs no affinity.
- **Token refresh** is a small explicit form POST to Keycloak's token endpoint.
  A `400`/`401` refresh response is authoritative rejection: log
  `token_refresh_failed`, destroy the Redis session, clear the cookie, and relay
  the original request anonymously. A network failure, invalid response, `5xx`,
  `429`, or any other IdP failure keeps the session and returns a `503` problem
  document.
- **`OIDC_INTERNAL_ISSUER_URI` is honored.** Browser redirects keep
  `OIDC_ISSUER_URI`; token, JWKS, and logout calls are re-based onto the
  internal issuer when set, matching compose's Keycloak networking.
- **Anonymous `/api/**` requests relay without a token.** The public API surface
  works logged-out; the backend owns all authorization decisions. Client-supplied
  `Authorization`, `Cookie`, and `X-XSRF-TOKEN` headers are stripped before
  proxying.
- **CSRF and same-origin checks** follow the shared double-submit contract:
  readable `XSRF-TOKEN` cookie plus `X-XSRF-TOKEN` header on `POST`/`PUT`/
  `PATCH`/`DELETE` `/api/**` calls, with `Origin` and `Sec-Fetch-Site` enforced
  against `PUBLIC_URL`.
- **Selective security headers.** SPA and `/auth/**` responses get the full
  browser-hardening set from `docs/ARCHITECTURE.md`; proxied `/api/**` responses
  get only `X-Content-Type-Options: nosniff` plus HTTPS-only HSTS, preserving
  backend `Cache-Control`, `ETag`, `Content-Language`, `304`, and bodies.
- **Observability** uses the OpenTelemetry Node SDK with HTTP instrumentation and
  OTLP traces/metrics/logs, enabled only when `OTEL_SDK_DISABLED=false`.
- **Logging** uses Pino: JSON console output by default, `pino-pretty` for
  `LOG_FORMAT=text`, and structured `event`/`outcome` fields for the contract
  events. Tokens, cookies, and client secrets are never logged.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (Node SDK) |
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

¹ `dependency_call_failed` is emitted for Keycloak refresh outages and upstream
proxy failures, but Redis connection/read/write failures are still uncovered as
structured dependency events.

## Configuration

All shared variables from [gateways/README.md](../README.md). `FRONTEND_URL`
is the normal path in compose and dev mode. If it is unset, `SPA_ROOT`
defaults to the bundled placeholder page.

## Run

```sh
# infra first, from the repo root: docker compose up -d
yarn install
yarn dev
# -> http://localhost:8000, log in as demo/demo
```

## Test

The unit/integration-style Vitest suite hosts Fastify in process and uses a
memory session store plus stubbed upstreams to pin the gateway contract:
OIDC redirects, callback failure behavior, token relay, CSRF/origin checks,
security headers, logout, and refresh rejection vs IdP outage.

```sh
yarn build
yarn test
```

## Docker

```sh
docker build -t stackverse/gateway-node-fastify:local .
# then from the repo root:
# GATEWAY_IMAGE=stackverse/gateway-node-fastify:local docker compose --profile app up
```
