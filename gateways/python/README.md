# Gateway: Python Starlette

The Stackverse BFF in Python 3.14 using [Starlette](https://www.starlette.io/),
[Authlib](https://docs.authlib.org/), httpx, Redis, and OpenTelemetry. Route
contract, cookie rules, and the login sequence live in
[docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md); shared env vars in
[gateways/README.md](../README.md).

## How it maps to the contract

| Contract | Here |
|---|---|
| `GET /auth/login` | handler creates Redis-backed one-time state + PKCE verifier, then redirects to Keycloak |
| `GET /auth/callback` | handler consumes state, exchanges the code, validates the ID token with joserfc JOSE, stores the session |
| `POST /auth/logout` | local Redis session deletion first, best-effort server-to-server Keycloak logout, `204` |
| `GET /auth/session` | reads the Redis-backed session behind `stackverse_session` |
| `/api/**` | Starlette catch-all proxies to `BACKEND_URL`, stripping browser cookies and client `Authorization` |
| `/**` | proxies `FRONTEND_URL` when set; otherwise serves `SPA_ROOT` or the bundled placeholder |

## Design notes

- **Stateless process.** The browser cookie carries only an opaque random id.
  Tokens, username, and OIDC login state live in Redis under `stackverse:*`, so
  any gateway instance can serve any request.
- **OIDC code flow with PKCE.** Browser redirects use `OIDC_ISSUER_URI`;
  Authlib provides the S256 PKCE helper, while server-side discovery, token,
  JWKS, and logout calls use `OIDC_INTERNAL_ISSUER_URI` when set. ID-token
  issuer validation still uses the public issuer.
- **Token refresh split.** A refresh rejected by Keycloak (`400`/`401`) logs
  `token_refresh_failed`, destroys the session, clears the cookie, and relays
  the original API request anonymously. A transient IdP outage (`5xx`, `429`,
  unreachable, or malformed response) logs `dependency_call_failed`, keeps the
  Redis session, and returns a `503` problem document.
- **CSRF and same-origin boundary.** State-changing `/api/**` requests require
  the readable `XSRF-TOKEN` cookie to match `X-XSRF-TOKEN`, and browser
  `Origin`/`Sec-Fetch-Site` signals must match the exact `PUBLIC_URL` origin.
- **Selective security headers.** Frontend and `/auth/**` responses receive the full
  browser hardening header set. Proxied `/api/**` responses receive only
  `X-Content-Type-Options: nosniff` plus HTTPS-only HSTS, preserving backend
  `Cache-Control`, `ETag`, `Content-Language`, `304`, and bodies.
- **Streaming proxy.** httpx streams upstream response bytes through Starlette,
  while Stackverse-specific policy stays limited to header stripping,
  bearer-token attachment, trace propagation, and problem responses for
  gateway-owned failures.
- **Observability.** When `OTEL_SDK_DISABLED=false`, Starlette/httpx
  instrumentation and OTLP exporters publish traces, metrics, and logs through
  the standard `OTEL_*` variables; proxied API requests receive W3C trace
  context from the active gateway span.
- **Logging** uses Python's standard logging with JSON console output by
  default, `LOG_FORMAT=text` for local development, and structured
  `event`/`outcome` fields for contract events. Tokens, cookies, and client
  secrets are never logged.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (OpenTelemetry SDK logging handler) |
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

¹ `dependency_call_failed` is emitted for Redis, Keycloak, backend, and
frontend dependency failures. There are no retry loops, so `retry_exhausted`
has no occurrence to log.

## Configuration

All shared variables from [gateways/README.md](../README.md). `FRONTEND_URL`
takes precedence over local static serving. When it is unset, `SPA_ROOT` points
at a production SPA build; if `SPA_ROOT` is also unset, the app serves a bundled
placeholder page.

## Run

```sh
# infra first, from the repo root: docker compose up -d
python -m venv .venv
. .venv/bin/activate
python -m pip install -e ".[test]"
python -m stackverse_gateway
# -> http://localhost:8000, log in as demo/demo
```

PowerShell activation uses `.venv\Scripts\Activate.ps1`.

## Test

The pytest suite hosts Starlette in process and uses a memory session store plus
stubbed upstreams to cover login, callback, token relay, CSRF/origin checks,
security headers, logout, and refresh rejection vs IdP outage. No Docker
services are required.

```sh
python -m compileall stackverse_gateway tests
python -m ruff check .
python -m ruff format --check .
python -m pytest
```

## Docker

```sh
docker build -t stackverse/gateway-python:local .
# then from the repo root:
# GATEWAY_IMAGE=stackverse/gateway-python:local docker compose --profile app up
```
