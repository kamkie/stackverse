# Gateways

One directory per implementation (`yarp`, `spring-cloud-gateway`, ...). The gateway is
the BFF: the OIDC client, the session owner, and the only component the browser talks
to. The full route contract and login flow live in
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## Responsibilities

- **OIDC client** — authorization code flow with PKCE against Keycloak
  (`/auth/login`, `/auth/callback`, `/auth/logout`, `/auth/session`).
- **Session owner** — `stackverse_session` cookie (`HttpOnly`, `SameSite=Lax`,
  `Secure` outside dev); session data (tokens) in Redis, so the gateway process
  itself stays stateless and horizontally scalable.
- **Token relay** — proxy `/api/**` to the backend, attaching the session's access
  token as `Authorization: Bearer ...`; refresh expired tokens transparently.
- **CSRF protection** for state-changing `/api/*` requests — mechanism is
  stack-specific, document it in the implementation README.
- **Frontend delivery** — serve the SPA build (or proxy a dev server) on `/**`.

The gateway makes no business decisions. If a request has a valid session it is
relayed; authorization is the backend's job.

Logging follows [docs/LOGGING.md](../docs/LOGGING.md) — in particular: tokens,
cookies and the client secret never appear in logs, and a token refresh the IdP
*rejected* (session destroyed, request degraded to anonymous) is a `WARN`, not an
error. An *unavailable* IdP — unreachable or answering `5xx`/`429` — is a
dependency failure: `ERROR`, session kept, request answered `503` (see
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)).

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8000` | HTTP listen port |
| `BACKEND_URL` | `http://localhost:8080` | upstream API |
| `FRONTEND_URL` | *(unset)* | SPA dev server to proxy; if unset, serve static files |
| `SPA_ROOT` | *(unset)* | directory of the SPA production build to serve when `FRONTEND_URL` is unset; if also unset, serve an implementation-bundled placeholder page (`compose.yaml` mounts the frontend image's build here) |
| `REDIS_URL` | `redis://localhost:6379` | session store |
| `OIDC_ISSUER_URI` | `http://localhost:8180/realms/stackverse` | IdP realm |
| `OIDC_CLIENT_ID` | `stackverse-gateway` | OIDC client id |
| `OIDC_CLIENT_SECRET` | `stackverse-secret` | OIDC client secret (dev value) |
| `PUBLIC_URL` | `http://localhost:8000` | external base URL for OIDC redirects |
| `OTEL_SDK_DISABLED` | `true` | set `false` to export traces/metrics/logs over OTLP; standard `OTEL_*` vars (`OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, ...) configure the export (see [docs/RUNNING.md](../docs/RUNNING.md)) |
| `LOG_LEVEL` | `info` | minimum console log severity: `error`, `warn`, `info`, `debug` ([docs/LOGGING.md](../docs/LOGGING.md)) |
| `LOG_FORMAT` | `json` | `text` opts into human-readable console output for local dev ([docs/LOGGING.md](../docs/LOGGING.md)) |

Ship a `Dockerfile`; the image plugs into `compose.yaml` via `GATEWAY_IMAGE`.
