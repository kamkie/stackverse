# Building and running Stackverse

Three ways to run the app, from lightest to fullest. All commands run from the
repo root unless noted.

## 1. Frontend only (mocked API) — fastest way to see the UI

No Docker, no backend — the API and the OIDC dance are mocked in the browser
(MSW). Good for UI work and demos.

```sh
cd frontends/react
yarn install
yarn dev          # http://localhost:5173
```

Pick who you are before clicking *Log in* (browser devtools console):

```js
localStorage.setItem("stackverse.mock.login-as", "demo");      // regular user
localStorage.setItem("stackverse.mock.login-as", "moderator"); // + reports, dashboard
localStorage.setItem("stackverse.mock.login-as", "admin");     // full backoffice
```

**Logs:** the dev server prints to the terminal, and the browser's console
output (plus uncaught errors) is forwarded to it (`/__client-log`, lines
prefixed `[browser]`). To also capture everything to a file:

```sh
# POSIX
yarn dev 2>&1 | tee dev-server.log
```

```powershell
# PowerShell
yarn dev 2>&1 | Tee-Object -FilePath dev-server.log
```

## 2. Infrastructure only

PostgreSQL, Redis, and Keycloak (realm pre-imported), for running a backend or
gateway from your IDE:

```sh
docker compose up -d
docker compose logs -f          # or: docker compose logs -f keycloak
```

## 3. Full stack (containers)

Build images and run any backend + gateway + frontend combination:

```sh
./scripts/build-images.sh                # spring-kotlin + yarp + react (defaults)
./scripts/run-stack.sh                   # or: BUILD=1 ./scripts/run-stack.sh
```

```powershell
./scripts/run-stack.ps1 -Build
```

Then open http://localhost:8000 and log in as `demo` / `demo`,
`moderator` / `moderator`, or `admin` / `admin`.

| Service | URL |
|---|---|
| App (gateway) | http://localhost:8000 |
| Backend API (direct) | http://localhost:8080 |
| Keycloak admin | http://localhost:8180 (`admin` / `admin`) |
| PostgreSQL | localhost:5432 (`stackverse` / `stackverse`) |

The stack runs attached; Ctrl+C stops it. **Logs:** `docker compose logs -f
[service]`, or Docker Desktop.

Manual image builds (what the script does):

```sh
# backend images build with the REPO ROOT as context (they bundle spec/messages)
docker build -t stackverse/backend-spring-kotlin:local -f backends/spring-kotlin/Dockerfile .
# gateway images build with their own directory as context
docker build -t stackverse/gateway-yarp:local gateways/yarp
# frontend images build with the REPO ROOT as context (they bundle spec/design)
docker build -t stackverse/frontend-react:local -f frontends/react/Dockerfile .
```

The frontend image is a file carrier, not a server: on `up` it copies its
static build into a shared volume and exits, and the gateway serves those
files (`SPA_ROOT`). The gateway waits for that copy before starting.

## Observability

An all-in-one [grafana/otel-lgtm](https://github.com/grafana/docker-otel-lgtm)
container (OTLP collector + Prometheus metrics + Tempo traces + Loki logs +
Grafana) ships behind the `observability` compose profile. Backends and
gateways speak standard `OTEL_*` env vars and stay silent unless
`OTEL_SDK_DISABLED=false`:

```sh
OBSERVABILITY=1 ./scripts/run-stack.sh
```

```powershell
./scripts/run-stack.ps1 -Observability
```

Grafana: http://localhost:3000 (`admin` / `admin`) — traces in Tempo, metrics
in Prometheus, logs in Loki, all pre-provisioned as data sources.

Per-implementation wiring:

- `backends/spring-kotlin` — OpenTelemetry Java agent baked into the image
  (auto-instruments Spring MVC, JDBC, logging).
- `gateways/yarp` — OpenTelemetry .NET SDK (ASP.NET Core + HttpClient
  instrumentation, OTLP for traces/metrics/logs).

The observability stack is dev-grade by design: one container, no
persistence, no auth hardening. Production wiring is out of scope (see
docs/INTENT.md).
