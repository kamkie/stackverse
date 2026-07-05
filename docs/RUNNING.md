# Building and running Stackverse

Three ways to run the app, from lightest to fullest. All commands run from the
repo root unless noted.

## 1. Frontend only (mocked API) — fastest way to see the UI

No Docker, no backend — the API and the OIDC dance are mocked in the browser.
Good for UI work and demos. Whether a frontend ships an in-browser mock mode
(and how to pick the mocked identity) is a per-implementation convenience
documented in that frontend's README — the React frontend has one (MSW);
frontends without one need a running gateway (mode 2b).

```sh
cd frontends/react     # or any frontend whose README documents a mock mode
yarn install
yarn dev               # http://localhost:5173
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

A `postgres-data` volume created before the postgres 18 bump (2026-07-03) is
17-format and unusable — if postgres exits at startup or comes up empty, wipe
and recreate: `docker compose down -v && docker compose up -d`.

The same wipe applies when switching which backend implementation runs against
the volume: each backend owns its schema and applies its own migrations
(backends/README.md), so a database initialized by one backend makes another
backend's migrations fail at startup.

## 2b. Full stack (dev mode, one terminal tab per module)

Infra in Docker, backend/gateway/frontend as live dev processes (hot reload)
in their own Windows Terminal tabs, each tee'ing its output to
`.logs/<module>.log` at the repo root:

```sh
./scripts/dev-stack.sh      # or: ./scripts/dev-stack.ps1
```

App on http://localhost:8000 (gateway proxies the Vite dev server). Stop with
Ctrl+C per tab and `docker compose down`.

The script starts the reference frontend (React). To develop against another
frontend implementation, run `yarn dev` in its directory in the frontend tab
instead — same port (5173), same gateway wiring; whether it has a mock toggle
is documented in its README.

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

## Local demo seed data

For a non-empty local stack, seed the running backend through the public API:

```sh
./scripts/seed-test-data.sh
```

```powershell
./scripts/seed-test-data.ps1
```

Defaults are `BACKEND_URL=http://localhost:8080` and
`KEYCLOAK_URL=http://localhost:8180`. Override them with environment
variables, or with shell flags. Node.js 18+ is required; set `NODE_BIN` when
the desired Node binary is not first on `PATH`.

```sh
BACKEND_URL=http://localhost:8080 KEYCLOAK_URL=http://localhost:8180 ./scripts/seed-test-data.sh
./scripts/seed-test-data.sh --backend-url http://localhost:8080 --keycloak-url http://localhost:8180
```

```powershell
./scripts/seed-test-data.ps1 -BackendUrl http://localhost:8080 -KeycloakUrl http://localhost:8180
```

The script uses the dev-only `stackverse-conformance` Keycloak client to get
tokens for `demo`, `mentor`, `moderator`, and `admin`, then creates data only
through documented API endpoints. It creates or updates seed-namespaced
bookmarks by exact title, so reruns do not add duplicate seed bookmarks. The
dataset includes public and private bookmarks for `demo` and `admin`, one
open report for the moderator queue, one bookmark hidden through the normal
moderation resolution flow, user accounts for the built-in dev users, top-tag
data, stats data, and at least one audit entry from the moderation action.

The seed is intentionally direct-to-backend: gateway sessions and CSRF are
exercised by the e2e suite, while the seed needs to work the same way for any
backend/gateway/frontend combination. To clear all local seed and test data,
reset the compose database volume:

```sh
docker compose down -v
docker compose up -d
```

Use the same reset when switching backend implementations, because each
backend owns its schema and migrations.

The stack runs attached; Ctrl+C stops it. **Logs:** `docker compose logs -f
[service]`, or Docker Desktop.

Manual image builds (what `scripts/build-images.sh` / `.ps1` does, one
`docker build` per layer). The only trap is the build context: **backend and
frontend images build with the repo root as context** (they bundle
`spec/messages` / `spec/design`), **gateway images with their own
directory**. One example per layer — every implementation's exact command is
in its own README:

```sh
docker build -t stackverse/backend-spring-kotlin:local -f backends/spring-kotlin/Dockerfile .
docker build -t stackverse/gateway-yarp:local gateways/yarp
docker build -t stackverse/frontend-react:local -f frontends/react/Dockerfile .
```

Non-default combinations pass the implementation names positionally, e.g. the
Angular frontend:

```sh
BUILD=1 ./scripts/run-stack.sh spring-kotlin yarp angular
```

The frontend image is a long-running static server on the compose network. It
is not published to the host; the gateway remains the only browser entry point
and proxies the SPA from `FRONTEND_URL=http://frontend:8080`. Direct navigation
to client-side routes through `http://localhost:8000` still returns the SPA
shell, while built assets are served normally by the frontend server.

## End-to-end tests

With a stack running (either mode above), the Playwright suite in `e2e/`
drives the real app — Keycloak login included — through every required screen:

```sh
./scripts/e2e.sh            # or: ./scripts/e2e.ps1
./scripts/e2e.sh --headed   # extra args go to `playwright test`
```

Point it at a different gateway with `STACKVERSE_URL=http://host:port`.

## Contract conformance tests

With just the infra and one backend running (mode 2 with the backend from your
IDE, or dev mode) — no gateway or frontend needed — the black-box suite in
`conformance/` checks the backend against [spec/openapi.yaml](../spec/openapi.yaml)
and [docs/SPEC.md](SPEC.md): role enforcement, ownership masking, the v1/v2
pagination exhibit with its deprecation headers, ETag revalidation, the
moderation state machine, blocking, audit, stats.

```sh
./scripts/conformance.sh                 # or: ./scripts/conformance.ps1
./scripts/conformance.sh -g pagination   # extra args go to `playwright test`
```

`BACKEND_URL` (default http://localhost:8080) and `KEYCLOAK_URL` (default
http://localhost:8180) point it elsewhere. The suite bypasses the gateway:
it takes per-role tokens straight from the dev realm's
`stackverse-conformance` password-grant client. That client ships in the
realm import — infra created before the client existed needs a one-time
`docker compose up -d --force-recreate keycloak` (dev Keycloak has no
persistent volume, so recreating re-imports the realm).

## OpenAPI property tests

With just infra and one backend running, the optional Schemathesis showcase in
[`testing/schemathesis-api/`](../testing/schemathesis-api) generates property
tests from [`spec/openapi.yaml`](../spec/openapi.yaml) and runs them directly
against `BACKEND_URL`:

```sh
./scripts/schemathesis-api.sh
```

```powershell
./scripts/schemathesis-api.ps1
```

Defaults are `BACKEND_URL=http://localhost:8080`,
`KEYCLOAK_URL=http://localhost:8180`, `SCHEMATHESIS_AUTH_ROLE=admin`,
`SCHEMATHESIS_MAX_EXAMPLES=20`, positive fuzzing only, and one worker. Extra
arguments are passed through to `st run`, so local exploration can raise the
example count or select checks:

```sh
SCHEMATHESIS_MAX_EXAMPLES=50 ./scripts/schemathesis-api.sh --checks not_a_server_error,status_code_conformance
```

Schemathesis failures include reproducible cases in the CLI output and crash
files under `testing/schemathesis-api/.schemathesis/`; reports are written to
`testing/schemathesis-api/schemathesis-report/`. The suite is a testing-tool
showcase for generated OpenAPI edge cases and response-schema checks. It does
not replace the semantic conformance suite, which remains the executable form
of `docs/SPEC.md`.

## Hurl API showcase

With just infra and one backend running, the optional Hurl showcase in
[`testing/hurl-api/`](../testing/hurl-api) runs readable plain-text HTTP
scenarios directly against `BACKEND_URL`:

```sh
./scripts/hurl-api.sh
```

```powershell
./scripts/hurl-api.ps1
```

Defaults are `BACKEND_URL=http://localhost:8080` and
`KEYCLOAK_URL=http://localhost:8180`. The Hurl file fetches dev tokens through
the same `stackverse-conformance` Keycloak client as `conformance/`, then
exercises representative public, authenticated, moderator, and admin API
flows. The wrapper scripts generate a unique `HURL_RUN_ID` for deterministic
scenario data; override it to reproduce a named run:

```sh
HURL_RUN_ID=hurl-local-demo ./scripts/hurl-api.sh -- --very-verbose
```

```powershell
$env:HURL_RUN_ID = "hurl-local-demo"
./scripts/hurl-api.ps1 --very-verbose
```

This suite is executable API documentation and a testing-tool comparison
example. It does not replace the canonical semantic conformance suite.

## OWASP ZAP baseline security smoke

With a full stack running, the optional ZAP showcase in
[`testing/zap-security/`](../testing/zap-security) runs the OWASP ZAP baseline
scan against the gateway at `STACKVERSE_URL`:

```sh
./scripts/zap-security.sh
```

```powershell
./scripts/zap-security.ps1
```

Defaults are `STACKVERSE_URL=http://localhost:8000`,
`ZAP_SPIDER_MINUTES=1`, and reports under
`testing/zap-security/reports/` as HTML, Markdown, and JSON. The helper runs
ZAP in Docker; for localhost targets it converts the in-container URL to
`host.docker.internal` while keeping `STACKVERSE_URL` as the human-facing
gateway URL. Set `ZAP_TARGET_URL` to override that target.

This is a passive baseline scan: ZAP spiders the site for a bounded time,
waits for passive scanners, and reports findings. It does not run active
attack testing. WARN-only findings do not fail the helper by default
(`ZAP_FAIL_ON_WARNINGS=false`) but remain visible in the reports; set
`ZAP_FAIL_ON_WARNINGS=true` when calibrating stricter local runs.

## Trace-based observability tests

The optional Tracetest showcase in
[`testing/tracetest-otel/`](../testing/tracetest-otel) runs a composed stack
with OpenTelemetry enabled and proves the architecture's trace propagation
rule: one API action through the gateway yields one trace with both gateway
and backend spans.

The helper starts the stack attached, runs the Tracetest runner, and stops any
started containers when the runner exits:

```sh
./scripts/tracetest-otel.sh
```

```powershell
./scripts/tracetest-otel.ps1
```

Build images first, or let the helper build them:

```sh
BUILD=1 ./scripts/tracetest-otel.sh
```

```powershell
./scripts/tracetest-otel.ps1 -Build
```

By default it runs `spring-kotlin + yarp + react`; positional arguments select
another backend, gateway, and frontend using the same image naming convention
as `scripts/run-stack.*`. The Tracetest overlay keeps stack ports internal to
the compose network, so it can run alongside a normal local Stackverse stack
that already owns the standard host ports.

The suite adds a small OpenTelemetry Collector that receives app telemetry,
fans traces out to both Grafana LGTM and Tracetest, and forwards logs/metrics
to LGTM. Tracetest sends `GET /api/v1/messages/bundle?lang=en` through the
gateway and asserts that `stackverse-gateway` and `stackverse-backend` spans
exist, with backend work descending from gateway work in the same trace.

Reports are written under `testing/tracetest-otel/reports/`. Expect roughly
2-4 minutes after images are present. The workflow
[`test-tracetest-otel.yml`](../.github/workflows/test-tracetest-otel.yml) is
manual-only (`workflow_dispatch`) so trace assertions remain opt-in and
non-blocking while the observability surface matures.

## Postman API showcase

With just infra and one backend running, the optional Postman collection in
[`testing/postman-api/`](../testing/postman-api) runs representative public,
authenticated, moderator, and admin API workflows directly against
`BACKEND_URL`:

```sh
cd testing/postman-api
corepack enable
yarn install --immutable
yarn test
```

Defaults are `BACKEND_URL=http://localhost:8080` and
`KEYCLOAK_URL=http://localhost:8180`. The collection acquires dev-user tokens
from the `stackverse-conformance` Keycloak client, creates unique per-run
data, and writes Newman JSON/JUnit reports under
`testing/postman-api/newman-report/`.

The same collection can be run with the Postman CLI:

```sh
postman collection run stackverse-api-showcase.postman_collection.json \
  --environment stackverse-local.postman_environment.json \
  --env-var "BACKEND_URL=http://localhost:8080" \
  --env-var "KEYCLOAK_URL=http://localhost:8180"
```

The suite is a testing-tool showcase for API exploration and team handoff. It
does not replace the semantic conformance suite.

## Testing-tool showcase suites

Stackverse has two canonical acceptance gates:

- [conformance/](../conformance) is the backend API gate. It runs directly
  against `BACKEND_URL` with dev-realm tokens and proves
  [spec/openapi.yaml](../spec/openapi.yaml) plus [docs/SPEC.md](SPEC.md).
- [e2e/](../e2e) is the composed-stack UI gate. It runs through the gateway at
  `STACKVERSE_URL`, drives the real Keycloak login, and proves every required
  frontend screen from [frontends/README.md](../frontends/README.md).

Additional suites under [testing/](../testing/README.md) are showcase variants
for comparing testing tools such as Selenium, Cypress, Schemathesis, Postman,
Hurl, Robot Framework, API collections, k6, ZAP, axe-core, or trace assertions.
They should choose representative public, authenticated, moderator, and admin
flows that show the tool's style. They are not a way to add new product
requirements or to replace the canonical gates.

The Schemathesis API showcase has a manual CI workflow,
[`test-schemathesis-api.yml`](../.github/workflows/test-schemathesis-api.yml),
with a selected backend input and bounded example count. It is deliberately
`workflow_dispatch` only, so it does not become an accidental merge gate through
`ci-ok`.

New showcase suites use `testing/<tool>-<scope>` and carry their own README,
default local command, and `.github/workflows/test-<tool>-<scope>.yml` when
they need CI. A workflow that runs on `push` or `pull_request` is effectively
gating because `ci-ok` waits for every GitHub Actions check run on the commit.
Keep immature showcase suites manual, scheduled, or failure-tolerant until the
repo deliberately promotes them to a required gate.

The Selenium showcase lives in [testing/selenium-e2e](../testing/selenium-e2e).
With a stack running at `STACKVERSE_URL` (default `http://localhost:8000`), run
it from that directory:

```sh
corepack enable
yarn install --immutable
yarn test
```

It drives Chrome through the real gateway and Keycloak login flow, covering
representative login/session, public feed, bookmark CRUD, reporting,
moderation, and admin-message workflows. Its CI workflow,
[`test-selenium-e2e.yml`](../.github/workflows/test-selenium-e2e.yml), is
manual-only (`workflow_dispatch`) so the suite stays optional and non-blocking.

The Cypress showcase lives in [testing/cypress-e2e](../testing/cypress-e2e)
and runs through the gateway at `STACKVERSE_URL` (default
`http://localhost:8000`), including the real Keycloak redirect/login flow:

```sh
cd testing/cypress-e2e
yarn install --immutable
yarn test
```

Set `KEYCLOAK_ORIGIN` when the gateway redirects to a Keycloak origin other
than http://localhost:8180; Cypress needs that origin for its `cy.origin()`
login block. CI execution is manual through
[test-cypress-e2e.yml](../.github/workflows/test-cypress-e2e.yml), which builds
the reference stack, runs the suite, and uploads Cypress artifacts on failure.
It is not part of the merge gate.

The Bruno API showcase lives in [testing/bruno-api](../testing/bruno-api) and
runs directly against a backend at `BACKEND_URL` (default
`http://localhost:8080`) with dev-realm tokens from `KEYCLOAK_URL` (default
`http://localhost:8180`):

```sh
cd testing/bruno-api
npm install
npm test
```

It is a checked-in OpenCollection YAML collection for representative public,
authenticated, moderation, and admin API workflows. It documents token
acquisition through the `stackverse-conformance` password-grant client and
stores tokens only as Bruno runtime variables during a run. There is no CI
workflow yet, so the suite remains a local API-client showcase and not a merge
gate.

The axe-core accessibility showcase lives in
[testing/axe-a11y](../testing/axe-a11y) and runs through the gateway at
`STACKVERSE_URL` (default `http://localhost:8000`). It uses Playwright plus
`@axe-core/playwright` to scan representative public, authenticated,
moderator, and admin screen states:

```sh
cd testing/axe-a11y
corepack enable
yarn install --immutable
yarn playwright install chromium
yarn test
```

Failures print the affected page/state, axe rule id, impact, help URL, and
selectors, and attach JSON details to the Playwright result. The suite is
limited to automatically detectable WCAG A/AA checks and does not replace
manual accessibility review. CI execution is manual through
[test-axe-a11y.yml](../.github/workflows/test-axe-a11y.yml), which builds the
reference stack, runs the suite, and uploads Playwright artifacts on failure.
It is not part of the merge gate.

The ZAP security showcase lives in
[testing/zap-security](../testing/zap-security). Its manual workflow,
[test-zap-security.yml](../.github/workflows/test-zap-security.yml), builds
the reference stack, runs the passive baseline scan against the gateway, and
uploads the HTML, Markdown, and JSON reports. It is not triggered by
`push` or `pull_request`, so it stays non-blocking while it is a showcase.

The Tracetest showcase lives in
[testing/tracetest-otel](../testing/tracetest-otel). It exercises the
observability contract from [ARCHITECTURE.md](ARCHITECTURE.md#observability)
and the trace-correlation assumptions in
[LOGGING.md](LOGGING.md#7-correlation) without redefining API or UI
correctness.

The Hurl API showcase lives in [testing/hurl-api](../testing/hurl-api), uses
`BACKEND_URL` plus `KEYCLOAK_URL`, and runs through `./scripts/hurl-api.sh` or
`./scripts/hurl-api.ps1`. It has no CI workflow yet and remains a local
optional showcase.

The Postman API showcase lives in
[testing/postman-api](../testing/postman-api) and runs directly against a
backend at `BACKEND_URL` (default `http://localhost:8080`) with Keycloak at
`KEYCLOAK_URL` (default `http://localhost:8180`):

```sh
cd testing/postman-api
corepack enable
yarn install --immutable
yarn test
```

The collection acquires dev-realm tokens, exercises representative public,
authenticated, moderator, and admin workflows, and writes Newman reports.
CI execution is manual through
[test-postman-api.yml](../.github/workflows/test-postman-api.yml), which builds
one selected backend, runs the suite, and uploads the reports. It is not part
of the merge gate.

## Continuous integration

CI runs on every push to `main` and every pull request, split so that shared
workflow files stay O(1) in the number of implementations — a new variant
adds its own workflow file and is otherwise picked up automatically. Four job
categories:

- **Per-implementation builds** — each implementation has its own workflow
  file, `.github/workflows/build-<layer>-<name>.yml`, running that stack's
  build and tests in its own toolchain (see the workflow file and the
  implementation README for specifics).
- **Conformance** ([`ci.yml`](../.github/workflows/ci.yml)) — a `discover`
  job lists `backends/*/Dockerfile` from the filesystem, and one matrix leg
  per discovered backend builds that backend's image, starts the compose
  infra plus the backend, and runs the `conformance/` suite against it
  directly.
- **E2E** ([`ci.yml`](../.github/workflows/ci.yml)) — the same discovery over
  `frontends/*/Dockerfile`: one composed-stack run per frontend (backend and
  gateway pinned to the reference implementations, spring-kotlin + yarp),
  building the images with `scripts/build-images.sh` and driving the `e2e/`
  Playwright suite through the gateway — frontends have no conformance suite
  of their own, so e2e is their acceptance gate.
- **`ci-ok`** ([`ci.yml`](../.github/workflows/ci.yml)) — the single merge
  gate: it fails if any of the jobs above (its own workflow's via `needs`,
  the per-implementation build workflows via the Checks API) failed or was
  cancelled, if an implementation directory has no `build-<layer>-<name>.yml`,
  or if a `build-*.yml` never produced a run for the commit (a missing or
  broken workflow creates no checks and must not pass silently). Branch
  protection requires `ci-ok` plus CodeQL — required checks never change as
  variants land.

All jobs run on every change (no path filters): the contract couples every
implementation to `spec/` and `docs/`. Playwright reports upload as workflow
artifacts when a suite fails.

Each per-implementation build also uploads unit/integration coverage to
[Codecov](https://codecov.io/gh/kamkie/stackverse) under a per-implementation
flag, in whatever report format that toolchain emits (see its build
workflow). `codecov.yml` also mirrors each implementation as a
[component](https://docs.codecov.com/docs/components) — same numbers sliced
yml-side, so PR comments and the dashboard break coverage down per
implementation without extra uploads. Coverage is informational only — see
`codecov.yml` at the repo root; the acceptance gate stays the conformance and
e2e suites. The upload needs a `CODECOV_TOKEN` repository secret.

Every job — including conformance and e2e — also submits its JUnit test
results to Codecov test analytics under the same flags, even when the tests
fail. The README implementation matrix shows a per-flag coverage badge for
each done implementation.

Two more automations live in `.github/`:

- [`workflows/codeql.yml`](../.github/workflows/codeql.yml) — CodeQL static
  analysis over Kotlin/Java, C#, Go, JS/TS, and the workflow files themselves,
  on every push/PR and weekly. Kotlin needs a real compile, so that matrix leg
  builds every Kotlin project; the rest scan buildless.
- [`dependabot.yml`](../.github/dependabot.yml) — weekly dependency PRs for
  every ecosystem (Gradle, NuGet, npm, pip, GitHub Actions, Dockerfiles, and
  the compose infra images), with minor/patch bumps grouped per ecosystem.

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

How each backend and gateway wires OpenTelemetry (agent vs SDK, which
instrumentations) is a per-implementation choice documented in that
implementation's README.

The observability stack is dev-grade by design: one container, no
persistence, no auth hardening. Production wiring is out of scope (see
docs/INTENT.md).
