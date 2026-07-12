# Gateway: Rust Axum

The Stackverse BFF in Rust 1.96 using [Axum](https://github.com/tokio-rs/axum),
Tower middleware, Redis, reqwest, and `tracing`/OpenTelemetry. Route contract,
cookie rules, and the login sequence live in
[docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md); shared env vars in
[gateways/README.md](../README.md).

## How it maps to the contract

| Contract | Here |
|---|---|
| `GET /auth/login` | handler creates Redis-backed one-time state + PKCE verifier, then redirects to Keycloak |
| `GET /auth/callback` | handler consumes state, exchanges the code, validates the ID token, stores the session |
| `POST /auth/logout` | handler destroys the local Redis session first, calls Keycloak logout best-effort, returns `204` |
| `GET /auth/session` | handler reads the Redis session behind `stackverse_session` |
| `/api/**` | Axum catch-all proxies to `BACKEND_URL`, stripping browser cookies and client `Authorization` |
| `/**` | proxies `FRONTEND_URL` when set; otherwise serves `SPA_ROOT` or the bundled placeholder |

## Design notes

- **Stateless process.** The browser cookie contains only an opaque session key.
  Tokens, username, and OIDC login state live in Redis under `stackverse:*`, so any
  gateway instance can serve any request.
- **OIDC code flow with PKCE.** Browser redirects use `OIDC_ISSUER_URI`; server-side
  token, JWKS, and logout calls use `OIDC_INTERNAL_ISSUER_URI` when set. ID-token
  issuer validation still uses the public issuer.
- **Token refresh split.** A refresh rejected by Keycloak (`400`/`401`) logs
  `token_refresh_failed`, destroys the session, clears the cookie, and relays the
  original API request anonymously. A transient IdP outage (`5xx`, `429`, bad
  response, or unreachable) logs `dependency_call_failed`, keeps the Redis session,
  and returns a `503` problem document.
- **CSRF and same-origin boundary.** State-changing `/api/**` requests require the
  readable `XSRF-TOKEN` cookie to match `X-XSRF-TOKEN`, and browser
  `Origin`/`Sec-Fetch-Site` signals must match the exact `PUBLIC_URL` origin.
- **Selective security headers.** Frontend and `/auth/**` responses receive the full
  browser hardening header set. Proxied `/api/**` responses receive only
  `X-Content-Type-Options: nosniff` plus HTTPS-only HSTS, preserving backend
  `Cache-Control`, `ETag`, `Content-Language`, `304`, and bodies.
- **Streaming proxy.** Request and response bodies are streamed through reqwest and
  Axum bodies; Stackverse-specific policy is limited to header stripping, bearer
  attachment, trace propagation, and problem responses for gateway-owned failures.
- **Observability.** When `OTEL_SDK_DISABLED=false`, spans and log records are
  exported through OTLP/HTTP using standard `OTEL_*` variables. Proxied API
  requests receive W3C trace context from the active gateway span.
- **HTTP client TLS stays explicit.** `reqwest` uses Rustls without a bundled
  provider and the binary/test harness install Rustls's Ring provider before
  constructing clients, avoiding an AWS-LC/CMake requirement in the Docker build.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10.

| Requirement | Status |
|---|---|
| stdout-only logging | ✅ |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (`opentelemetry-appender-tracing` → OTLP/HTTP) |
| lifecycle events at `INFO` | ✅ |
| expected 4xx not logged as errors | ✅ |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | ✅ |
| trace id on console lines when tracing on | ✅ (tracing spans are bridged to OpenTelemetry; console includes active span context) |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ✅¹ |
| JSON console by default (`LOG_FORMAT`) | ✅ |
| dev-only console forwarding, sanitized | n/a |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a |

¹ `dependency_call_failed` is emitted for Redis, Keycloak, backend, and frontend
upstream failures. There are no retry loops, so `retry_exhausted` has no occurrence
to log.

## Configuration

All shared variables from [gateways/README.md](../README.md). `FRONTEND_URL`
is the normal path in compose and dev mode. If it is unset, `SPA_ROOT` points at
a production SPA build; if `SPA_ROOT` is also unset, the binary serves a bundled
placeholder page.

## Run

```sh
# infra first, from the repo root: docker compose up -d
cargo run
# -> http://localhost:8000, log in as demo/demo
```

## Test

The Rust suite combines in-process Axum contract requests, local backend/IdP
servers, a local RESP fixture that exercises the real Redis client, and
spawned-binary startup/OTLP/log-redaction tests. It covers login, callback,
session persistence, token relay and refresh, CSRF/origin checks, security
headers, proxy fidelity, post-bind lifecycle logging, and secret-safe effective
configuration. No Docker services are required.

```sh
cargo fmt --check
cargo check --locked
cargo test --locked
cargo llvm-cov --locked --lcov --output-path coverage/lcov.info
```

The coverage command requires `cargo-llvm-cov`.

## Docker

```sh
docker build -t stackverse/gateway-rust:local .
# then from the repo root:
# GATEWAY_IMAGE=stackverse/gateway-rust:local docker compose --profile app up
```
