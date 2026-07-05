# Gateway: Go (stdlib + chi)

The Stackverse BFF in Go 1.26 using `net/http`, chi, go-redis, `oauth2`, and
`go-oidc`. Route contract, cookie rules, and the login sequence live in
[docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md); shared env vars in
[gateways/README.md](../README.md).

## How it maps to the contract

| Contract | Here |
|---|---|
| `GET /auth/login` | handler creates Redis-backed one-time state + PKCE verifier, then redirects to Keycloak |
| `GET /auth/callback` | handler consumes state, exchanges code with the verifier, validates the ID token, stores the session |
| `POST /auth/logout` | local Redis session deletion first, best-effort server-to-server Keycloak logout, `204` |
| `GET /auth/session` | reads the Redis-backed session behind `stackverse_session` |
| `/api/**` | `httputil.ReverseProxy` to `BACKEND_URL`, stripping browser cookies and client `Authorization` |
| `/**` | proxies `FRONTEND_URL` when set; otherwise serves `SPA_ROOT` or the bundled placeholder |

## Design notes

- **Stateless process.** The browser cookie contains only an opaque session key.
  Tokens, username, and OIDC login state live in Redis under `stackverse:*`, so any
  gateway instance can serve any request.
- **OIDC code flow with PKCE.** The authorization redirect uses
  `OIDC_ISSUER_URI`; server-side token/JWKS/logout calls use
  `OIDC_INTERNAL_ISSUER_URI` when set, while ID token issuer validation still uses
  the public issuer. The one-time state and PKCE verifier live in Redis, and a
  short-lived HttpOnly `stackverse_login_state` cookie binds the callback to the
  browser that initiated login.
- **Refresh failure split.** A refresh rejected by Keycloak (`400`/`401`) logs
  `token_refresh_failed`, destroys the session, clears the cookie, and relays the
  original API request anonymously. A transient IdP outage (`5xx`, `429`,
  unreachable, or malformed token response) logs `dependency_call_failed`, keeps
  the Redis session, and returns a `503` problem document.
- **CSRF and same-origin boundary.** State-changing `/api/**` requests require the
  readable `XSRF-TOKEN` cookie to match `X-XSRF-TOKEN`, and browser
  `Origin`/`Sec-Fetch-Site` signals must match the exact `PUBLIC_URL` origin.
  Rejections return `403 application/problem+json` and log
  `csrf_validation_failed`.
- **Security headers.** SPA and `/auth/**` responses receive the full browser
  hardening header set. Proxied `/api/**` responses receive only
  `X-Content-Type-Options: nosniff` plus HTTPS-only HSTS, leaving backend cache,
  ETag, `304`, and body semantics untouched.
- **Observability.** The Go OpenTelemetry SDK exports traces, metrics, and logs
  over OTLP only when `OTEL_SDK_DISABLED=false`; proxied API requests use an
  instrumented transport so W3C `traceparent` reaches the backend.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ |
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

¹ `dependency_call_failed` is emitted for Redis, Keycloak token-refresh/logout,
backend, and frontend upstream failures. `retry_exhausted` is not emitted because
the gateway does not implement retries.

## Configuration

All shared variables from [gateways/README.md](../README.md). `FRONTEND_URL`
takes precedence over local static serving. When it is unset, `SPA_ROOT` points
at a production SPA build; if `SPA_ROOT` is also unset, the binary serves a
small embedded placeholder page.

## Run

```sh
# infra first, from the repo root: docker compose up -d
go run ./cmd/gateway
# -> http://localhost:8000, log in as demo/demo
```

## Test

The unit/integration tests use `httptest` for the backend, frontend, and OIDC
provider; no Docker services are required.

```sh
go test ./...
```

## Docker

```sh
docker build -t stackverse/gateway-go:local .
# then from the repo root:
# GATEWAY_IMAGE=stackverse/gateway-go:local docker compose --profile app up
```
