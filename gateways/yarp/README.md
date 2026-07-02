# Gateway: YARP (ASP.NET Core)

The Stackverse BFF on .NET 10 / ASP.NET Core with [YARP](https://github.com/dotnet/yarp)
as the reverse proxy. Route contract, cookie rules, and the login sequence live in
[docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md); shared env vars in
[gateways/README.md](../README.md).

## How it maps to the contract

| Contract | Here |
|---|---|
| `GET /auth/login` | minimal-API endpoint issuing an OIDC `Challenge` (code flow + PKCE S256) |
| `GET /auth/callback` | the `OpenIdConnect` handler's `CallbackPath` (`response_mode=query`) |
| `POST /auth/logout` | endpoint: server-to-server RP-initiated logout at Keycloak, cookie sign-out, `204` |
| `GET /auth/session` | endpoint reading the cookie principal (`preferred_username`) |
| `/api/**` | YARP route to `BACKEND_URL` with a Bearer-token request transform |
| `/**` | YARP route to `FRONTEND_URL` when set; otherwise static files + `index.html` fallback |

## Design notes

- **Stateless process.** The `stackverse_session` cookie carries only an opaque key;
  the authentication ticket (tokens included) lives in Redis via an `ITicketStore`
  over `IDistributedCache`. Data Protection keys are persisted to Redis too, so any
  instance can decrypt any cookie and any in-flight OIDC state — two gateways behind
  a dumb load balancer need no affinity.
- **Token refresh** is a hand-rolled ~50-line call to the token endpoint
  ([AccessTokenManager.cs](src/StackverseGateway/AccessTokenManager.cs)) instead of a
  Duende.AccessTokenManagement dependency: the exchange is one form POST, and this
  repo optimizes for self-contained, readable code. Concurrent requests may refresh
  twice; Keycloak allows refresh-token reuse by default, so both results are valid.
- **Logout without a redirect.** The contract wants `204` from `POST /auth/logout`,
  so RP-initiated logout happens server-to-server: a confidential-client POST of the
  refresh token to Keycloak's end-session endpoint tears down the SSO session.
- **PAR is disabled.** .NET auto-upgrades to Pushed Authorization Requests when the
  IdP advertises them; the plain front-channel authorization request is kept so all
  gateway stacks exhibit identical wire behavior.
- **Logout semantics: local-first, IdP best-effort.** Logout is three separate
  deaths: the gateway session (Redis ticket + cookie — the only credential the
  browser holds), the Keycloak SSO session (a backchannel network call), and the
  already-issued access token (dies only by expiry, ≤5 min — neither ordering
  changes that). The local session is destroyed *first* because it is the only
  death the gateway can guarantee; the IdP revocation follows, detached from the
  request abort and with failures swallowed. The asymmetry that justifies the
  order: if revocation fails, the worst case is that the same browser can
  re-login without a password until Keycloak's SSO idle timeout — a bounded
  footnote. The reverse order's worst case is a browser that stays logged in
  after the user asked not to be — logout as fiction, the exact failure this
  repo argues against. Killing Keycloak's browser cookie deterministically would
  require front-channel logout, which the `204` contract deliberately trades away;
  if the residual SSO window ever matters, that is a contract-level decision for
  all gateways, not a local one.
- **Logging** (docs/LOGGING.md) uses the built-in console formatters:
  `AddJsonConsole` by default (UTC RFC 3339 timestamps; activity tracking puts
  `TraceId`/`SpanId` into the logged scopes), `AddSimpleConsole` for
  `LOG_FORMAT=text`. The contract's `event`/`outcome` fields travel as
  structured logging state via the small `EventLog` extension; framework
  categories stay capped at `Warning` so per-request hosting noise (health
  probes included) stays out of INFO logs.
- **Anonymous `/api/**` requests relay without a token.** The spec's public surface
  (public bookmark feeds, message reads) works logged-out; the backend owns
  per-endpoint auth. A session that can no longer refresh is destroyed and the
  request degrades to anonymous. CSRF violations → `403` problem document
  (mechanism pinned in [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md)), and
  the browser's cookies and the CSRF header are stripped before proxying.

## Configuration

All shared variables from [gateways/README.md](../README.md). `SPA_ROOT`
defaults to the bundled `wwwroot` (a placeholder page) when unset.

## Run

```sh
# infra first, from the repo root: docker compose up -d
dotnet run --project src/StackverseGateway
# → http://localhost:8000, log in as demo/demo
```

## Test

Integration tests boot real Keycloak (realm imported from `infra/keycloak`) and Redis
via Testcontainers, host the gateway with `WebApplicationFactory`, and drive the real
authorization code flow plus token relay against a stub backend. Docker required.

```sh
dotnet test
```

## Docker

```sh
docker build -t stackverse/gateway-yarp:local .
# then from the repo root:
# GATEWAY_IMAGE=stackverse/gateway-yarp:local docker compose --profile app up
```
