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
- **No session on `/api/**` → `401`** `application/problem+json`, never a redirect;
  CSRF violations → `403` problem document (mechanism pinned in
  [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md)).

## Configuration

All shared variables from [gateways/README.md](../README.md), plus:

| Variable | Default | Purpose |
|---|---|---|
| `SPA_ROOT` | `wwwroot` (bundled placeholder page) | directory of the SPA production build to serve when `FRONTEND_URL` is unset |

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
