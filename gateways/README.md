# Gateways

One directory per implementation (`yarp`, `spring-cloud-gateway`, `go`, ...). The gateway is
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
- **Same-origin browser boundary** — no CORS support, no `Access-Control-Allow-*`
  headers, and affirmative `Origin` / `Sec-Fetch-Site` validation for
  state-changing `/api/**` requests against the `PUBLIC_URL` origin.
- **Browser hardening headers** — the shared header set from
  [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) on SPA/auth responses, plus
  global `X-Content-Type-Options: nosniff` and HTTPS-only HSTS without rewriting
  proxied API cache or ETag headers.
- **Frontend delivery** — proxy the SPA upstream on `/**`; standalone gateway
  runs may optionally serve a local static build as a fallback.

The gateway makes no business decisions. If a request has a valid session it is
relayed; authorization is the backend's job.

Logging follows [docs/LOGGING.md](../docs/LOGGING.md) — in particular: tokens,
cookies and the client secret never appear in logs, and a token refresh the IdP
*rejected* (session destroyed, request degraded to anonymous) is a `WARN`, not an
error. An *unavailable* IdP — unreachable or answering `5xx`/`429` — is a
dependency failure: `ERROR`, session kept, request answered `503` (see
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)).

## Considered alternatives: off-the-shelf gateways

As of 2026-07, ready-to-use gateway products were evaluated against the
Stackverse gateway contract. The conclusion was that no off-the-shelf product
satisfies the contract by configuration alone. The mismatch is the
application-specific BFF surface: exact `/auth/*` routes and JSON shapes,
`POST /auth/logout` returning `204`, Redis-backed server-side sessions, token
relay with anonymous fallback, double-submit CSRF, `SPA_ROOT` / `FRONTEND_URL`
delivery, distinguishable refresh rejection vs IdP unavailability, and the
logging / OpenTelemetry contract.

- **oauth2-proxy** is closest in spirit: it supports OIDC, Redis sessions, token
  relay, refresh, and a configurable proxy prefix. It still misses the exact
  Stackverse route names and response shapes, has no built-in double-submit CSRF
  contract, models auth as enforce-or-skip rather than token relay with
  anonymous fallback, and does not expose refresh rejection vs IdP unavailability
  as the gateway contract requires. It would be viable only with a companion shim,
  which was rejected as two processes pretending to be one gateway.
- **Envoy's built-in OAuth2 filter** stores access and refresh tokens in browser
  cookies. That violates the core contract that tokens never reach the browser
  and that session state lives server-side in Redis.
- **Pomerium** relays its own signed JWT upstream instead of the IdP access token.
  Stackverse backends validate Keycloak-issued bearer tokens against the Keycloak
  JWKS and read roles from those tokens, so this does not fit the backend contract.
- **Kong OSS** does not provide a maintained open-source OIDC relying-party path:
  Kong's OIDC plugin is enterprise-only, while the community `kong-oidc` plugin
  is unmaintained. That leaves the Stackverse session, relay, and route contract
  outside the product's supported OSS surface.
- **Traefik / Caddy** do not satisfy the contract as single OSS gateway products.
  Traefik OSS has no native OIDC relying party, while common Caddy auth setups
  issue their own auth-layer tokens. Both end up needing oauth2-proxy or another
  companion auth service for the BFF behavior Stackverse requires.
- **HAProxy** Community Edition has JWT validation and can host Lua, but it is not
  an OIDC relying party and has no `lua-resty-openidc` equivalent for HAProxy Lua.
  The authorization code flow, JWKS handling, refresh logic, and Redis session
  store would all be hand-written. Existing community patterns either store
  tokens in local HAProxy maps, making the gateway process stateful, or use an
  external SPOE agent with encrypted browser cookies, which still sends tokens to
  the browser. HAProxy Enterprise has native OIDC SSO with code flow and refresh,
  but uses ciphered-cookie sessions instead of the required external session
  store, and commercial licensing cannot run in this repo's compose / CI runtime.
- **F5 BIG-IP APM** is the enterprise-appliance data point: it can act as an OIDC
  client, keep an appliance-side session, and inject `Authorization: Bearer`
  upstream. It still fails the Stackverse contract because sessions live in the
  appliance's session table rather than Redis, `/auth/session`, logout, and
  double-submit CSRF would need iRules, and a licensed VM appliance cannot run in
  docker compose or GitHub Actions. Demo licensing exists, including 30-day BIG-IP
  VE trials and the limited APM tier included with LTM licenses, but that does not
  make it a runnable repo dependency.
- **Kubernetes-native gateways** are rejected as a category for this repository:
  [docs/INTENT.md](../docs/INTENT.md) keeps local docker compose as the supported
  runtime and treats deployment showcases as a non-goal. The auth findings are
  still useful comparison material. Gateway API itself standardizes auth
  delegation through GEP-1494 `ExternalAuth`, an `ext_authz`-style HTTPRoute
  filter, so the Kubernetes-standard shape is still "gateway plus BFF/auth
  component." Envoy Gateway is the notable config-only OIDC relying-party option
  with code flow, automatic refresh, and access-token forwarding, but it is built
  on Envoy's OAuth2 filter and stores tokens in encrypted browser cookies, with a
  known failure mode when large IdP tokens exceed practical cookie limits. Istio,
  Cilium, kgateway, and NGINX Gateway Fabric provide JWT validation and/or auth
  delegation but no OSS OIDC relying party; Ambassador Edge Stack, Gloo, Traefik
  Hub, and Kong provide OIDC in commercial tiers. APISIX's ingress controller uses
  the same OSS `lua-resty-openidc` path as standalone APISIX. ingress-nginx was
  retired as read-only after March 2026, and its OIDC story was always
  `auth_request` plus oauth2-proxy.
- **OpenResty / Apache APISIX** are the credible middle path: configuration plus
  Lua can meet the exact BFF contract instead of forcing a separate companion
  process. APISIX packages the same `lua-resty-openidc` foundation and includes a
  double-submit CSRF plugin, but the Stackverse `/auth/*` routes still require
  custom Lua snippets and SPA delivery is less direct. Plain OpenResty was chosen
  as the clearer infrastructure-as-gateway variant to pursue in
  [#62](https://github.com/kamkie/stackverse/issues/62).

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8000` | HTTP listen port |
| `BACKEND_URL` | `http://localhost:8080` | upstream API |
| `FRONTEND_URL` | *(unset)* | SPA upstream to proxy: the frontend static server in container mode, or a dev server in dev mode; if unset, serve the gateway's static-file fallback |
| `SPA_ROOT` | *(unset)* | optional directory of a SPA production build to serve only when `FRONTEND_URL` is unset; if also unset, serve an implementation-bundled placeholder page |
| `REDIS_URL` | `redis://localhost:6379` | session store |
| `OIDC_ISSUER_URI` | `http://localhost:8180/realms/stackverse` | IdP realm |
| `OIDC_INTERNAL_ISSUER_URI` | *(unset)* | optional base URL for the gateway's own IdP calls (discovery, token, JWKS, logout) when the public issuer host is not dialable from the gateway's network; issuer validation and the browser-facing authorization redirect keep `OIDC_ISSUER_URI`. compose sets it to the keycloak service; gateways whose HTTP client retries every resolved address (yarp) may ignore it |
| `OIDC_CLIENT_ID` | `stackverse-gateway` | OIDC client id |
| `OIDC_CLIENT_SECRET` | `stackverse-secret` | OIDC client secret (dev value) |
| `PUBLIC_URL` | `http://localhost:8000` | external base URL for OIDC redirects; canonical same-origin origin for browser API validation; `https` enables `Secure` cookies and HSTS |
| `OTEL_SDK_DISABLED` | `true` | set `false` to export traces/metrics/logs over OTLP; standard `OTEL_*` vars (`OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, ...) configure the export (see [docs/RUNNING.md](../docs/RUNNING.md)) |
| `LOG_LEVEL` | `info` | minimum console log severity: `error`, `warn`, `info`, `debug` ([docs/LOGGING.md](../docs/LOGGING.md)) |
| `LOG_FORMAT` | `json` | `text` opts into human-readable console output for local dev ([docs/LOGGING.md](../docs/LOGGING.md)) |

Ship a `Dockerfile`; the image plugs into `compose.yaml` via `GATEWAY_IMAGE`.
