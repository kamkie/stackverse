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
| Redis | localhost:6379 |
| Grafana | http://localhost:3000 (`admin` / `admin`, `observability` profile) |

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

## Testing-tool showcase suites

Stackverse has two canonical acceptance gates, documented above:

- [conformance/](../conformance) is the backend API gate. It runs directly
  against `BACKEND_URL` with dev-realm tokens and proves
  [spec/openapi.yaml](../spec/openapi.yaml) plus [docs/SPEC.md](SPEC.md).
- [e2e/](../e2e) is the composed-stack UI gate. It runs through the gateway at
  `STACKVERSE_URL`, drives the real Keycloak login, and proves every required
  frontend screen from [frontends/README.md](../frontends/README.md).

Everything below is an *optional showcase* under
[testing/](../testing/README.md): a variant for comparing testing tools such as
Selenium, Cypress, Schemathesis, Postman, Bruno, Hurl, Robot Framework, k6,
ZAP, axe-core, or trace assertions. Each is documented once. Most cover
representative public, authenticated, moderator, and admin flows that show the
tool's style; a few are narrower by nature (ZAP is a passive baseline scan,
Tracetest asserts one trace). None of them is a way to add new product
requirements or to replace the canonical gates.

New showcase suites use `testing/<tool>-<scope>` and carry their own README,
default local command, and `.github/workflows/test-<tool>-<scope>.yml` when they
need CI. A workflow that runs on `push` or `pull_request` is effectively gating
because `ci-ok` waits for every GitHub Actions check run on the commit. Keep
immature showcase suites manual, scheduled, or failure-tolerant until the repo
deliberately promotes them to a required gate — which is why every suite below
uses a `workflow_dispatch`-only workflow (or has none yet).

### API-contract showcases (direct to a backend)

These need only the compose infra and one backend — no gateway or frontend —
and run against `BACKEND_URL` (default `http://localhost:8080`) with dev-realm
tokens from `KEYCLOAK_URL` (default `http://localhost:8180`).

**Schemathesis (OpenAPI property tests)** —
[testing/schemathesis-api/](../testing/schemathesis-api) generates property
tests from [spec/openapi.yaml](../spec/openapi.yaml) and runs them directly
against the backend:

```sh
./scripts/schemathesis-api.sh
```

```powershell
./scripts/schemathesis-api.ps1
```

Defaults are `SCHEMATHESIS_AUTH_ROLE=admin`, `SCHEMATHESIS_MAX_EXAMPLES=20`,
positive fuzzing only, and one worker. Extra arguments pass through to `st run`,
so local exploration can raise the example count or select checks:

```sh
SCHEMATHESIS_MAX_EXAMPLES=50 ./scripts/schemathesis-api.sh --checks not_a_server_error,status_code_conformance
```

Failures include reproducible cases in the CLI output and crash files under
`testing/schemathesis-api/.schemathesis/`; reports are written to
`testing/schemathesis-api/schemathesis-report/`. It exercises generated OpenAPI
edge cases and response-schema checks — a testing-tool showcase, not a
replacement for the semantic conformance gate. Its CI workflow,
[test-schemathesis-api.yml](../.github/workflows/test-schemathesis-api.yml), is
`workflow_dispatch` only, with a selected backend input and bounded example
count, so it does not become an accidental merge gate through `ci-ok`.

**Hurl (plain-text HTTP scenarios)** — [testing/hurl-api](../testing/hurl-api)
runs readable plain-text HTTP scenarios directly against the backend:

```sh
./scripts/hurl-api.sh
```

```powershell
./scripts/hurl-api.ps1
```

The Hurl file fetches dev tokens through the `stackverse-conformance` Keycloak
client, then exercises representative public, authenticated, moderator, and
admin API flows. The wrapper scripts generate a unique `HURL_RUN_ID` for
deterministic scenario data; override it to reproduce a named run:

```sh
HURL_RUN_ID=hurl-local-demo ./scripts/hurl-api.sh -- --very-verbose
```

```powershell
$env:HURL_RUN_ID = "hurl-local-demo"
./scripts/hurl-api.ps1 --very-verbose
```

It is executable API documentation and a testing-tool comparison example. It
has no CI workflow yet and remains a local optional showcase.

**Postman (Newman)** — [testing/postman-api](../testing/postman-api) runs
representative public, authenticated, moderator, and admin API workflows:

```sh
cd testing/postman-api
corepack enable
yarn install --immutable
yarn test
```

The collection acquires dev-realm tokens from the `stackverse-conformance`
client, creates unique per-run data, and writes Newman JSON/JUnit reports under
`testing/postman-api/newman-report/`. The same collection runs with the Postman
CLI:

```sh
postman collection run stackverse-api-showcase.postman_collection.json \
  --environment stackverse-local.postman_environment.json \
  --env-var "BACKEND_URL=http://localhost:8080" \
  --env-var "KEYCLOAK_URL=http://localhost:8180"
```

CI execution is manual through
[test-postman-api.yml](../.github/workflows/test-postman-api.yml), which builds
one selected backend, runs the suite, and uploads the reports.

**Bruno (OpenCollection)** — [testing/bruno-api](../testing/bruno-api) is a
checked-in OpenCollection YAML collection for representative public,
authenticated, moderation, and admin API workflows:

```sh
cd testing/bruno-api
npm install
npm test
```

It documents token acquisition through the `stackverse-conformance`
password-grant client and stores tokens only as Bruno runtime variables during
a run. There is no CI workflow yet, so it remains a local API-client showcase.

### Browser and system showcases (through the gateway)

These need a full stack running and drive the gateway at `STACKVERSE_URL`
(default `http://localhost:8000`), including the real Keycloak login flow.

**Selenium** — [testing/selenium-e2e](../testing/selenium-e2e) drives Chrome
through the real gateway and Keycloak login flow, covering representative
login/session, public feed, bookmark CRUD, reporting, moderation, and
admin-message workflows:

```sh
cd testing/selenium-e2e
corepack enable
yarn install --immutable
yarn test
```

Its CI workflow,
[test-selenium-e2e.yml](../.github/workflows/test-selenium-e2e.yml), is
manual-only (`workflow_dispatch`) so the suite stays optional and non-blocking.

**Cypress** — [testing/cypress-e2e](../testing/cypress-e2e) runs through the
gateway including the real Keycloak redirect/login flow:

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

**Robot Framework** — [testing/robot-acceptance](../testing/robot-acceptance)
demonstrates Robot's keyword-driven style for representative login/session,
bookmark CRUD, reporting, moderation, and admin-navigation checks:

```sh
cd testing/robot-acceptance
python -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
python -m robot --outputdir results tests
```

PowerShell uses the same suite-local command with
`.\.venv\Scripts\Activate.ps1`. Standard Robot artifacts (`output.xml`,
`log.html`, `report.html`, plus failure screenshots) are written under
`testing/robot-acceptance/results/`. CI execution is manual through
[test-robot-acceptance.yml](../.github/workflows/test-robot-acceptance.yml),
which builds the reference stack, runs the suite, and uploads Robot artifacts.

**axe-core (accessibility)** — [testing/axe-a11y](../testing/axe-a11y) uses
Playwright plus `@axe-core/playwright` to scan representative public,
authenticated, moderator, and admin screen states:

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

**k6 (light load / system)** — [testing/k6-system](../testing/k6-system) has a
one-shot smoke script and a deliberately small light-load script: public feed
and message-bundle reads, real gateway-to-Keycloak login, CSRF-protected
bookmark setup/cleanup, authenticated user reads, and a moderator read check in
smoke mode:

```sh
./scripts/k6-system.sh
```

```powershell
./scripts/k6-system.ps1
```

Defaults are intentionally modest: `K6_DURATION=30s`, one public VU, one
authenticated VU, zero unexpected `5xx`, fewer than 1% unexpected statuses, and
p95 below `K6_P95_MS` (default 1500 ms) for tagged smoke/steady traffic. The
numbers are smoke and regression signals only, not benchmark claims or stack
rankings. CI execution is manual through
[test-k6-system.yml](../.github/workflows/test-k6-system.yml), which builds the
reference stack and uploads the k6 summary artifact.

### Security and observability showcases

**OWASP ZAP (baseline security smoke)** —
[testing/zap-security](../testing/zap-security) runs the OWASP ZAP baseline
scan against the gateway at `STACKVERSE_URL`, and needs a full stack running:

```sh
./scripts/zap-security.sh
```

```powershell
./scripts/zap-security.ps1
```

Defaults are `STACKVERSE_URL=http://localhost:8000`, `ZAP_SPIDER_MINUTES=1`,
and reports under `testing/zap-security/reports/` as HTML, Markdown, and JSON.
The helper runs ZAP in Docker; for localhost targets it converts the
in-container URL to `host.docker.internal` while keeping `STACKVERSE_URL` as the
human-facing gateway URL. Set `ZAP_TARGET_URL` to override that target. This is
a passive baseline scan: ZAP spiders the site for a bounded time, waits for
passive scanners, and reports findings — it does not run active attack testing.
WARN-only findings do not fail the helper by default
(`ZAP_FAIL_ON_WARNINGS=false`) but remain visible in the reports; set
`ZAP_FAIL_ON_WARNINGS=true` when calibrating stricter local runs. Its manual
workflow, [test-zap-security.yml](../.github/workflows/test-zap-security.yml),
builds the reference stack, runs the passive baseline scan, and uploads the
HTML, Markdown, and JSON reports. It is not triggered by `push` or
`pull_request`, so it stays non-blocking while it is a showcase.

**Tracetest (trace assertions)** —
[testing/tracetest-otel](../testing/tracetest-otel) runs a composed stack with
OpenTelemetry enabled and proves the architecture's trace propagation rule: one
API action through the gateway yields one trace with both gateway and backend
spans. The helper starts the stack attached, runs the Tracetest runner, and
stops any started containers when the runner exits:

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

Reports are written under `testing/tracetest-otel/reports/`; expect roughly
2-4 minutes after images are present. It exercises the observability contract
from [ARCHITECTURE.md](ARCHITECTURE.md#observability) and the trace-correlation
assumptions in [LOGGING.md](LOGGING.md#7-correlation) without redefining API or
UI correctness. The workflow
[test-tracetest-otel.yml](../.github/workflows/test-tracetest-otel.yml) is
manual-only (`workflow_dispatch`) so trace assertions remain opt-in and
non-blocking while the observability surface matures.

## Continuous integration

CI runs on every push to `main`, every pull request, a weekly schedule, and
manual dispatch. Shared workflow files stay O(1) in the number of
implementations: a new variant adds its own workflow file and is otherwise
picked up automatically. Pull requests run the affected subset, while pushes
to `main`, scheduled runs, and manual runs keep the full sweep.
Documentation-only pull requests do not select implementation variants for
builds, conformance, or e2e; `ci-ok` still runs and records that no variant
workflow is expected. Five job categories:

- **Per-implementation builds** — each implementation has its own workflow
  file, `.github/workflows/build-<layer>-<name>.yml`, running that stack's
  build and tests in its own toolchain (see the workflow file and the
  implementation README for specifics). On pull requests these workflows use
  path filters: an implementation build runs when its own workflow file, its
  implementation directory, or `spec/openapi.yaml` (for backends and
  frontends) changes. Pushes to `main` still run every implementation build.
- **Conformance** ([`ci.yml`](../.github/workflows/ci.yml)) — a `discover`
  job lists `backends/*/Dockerfile` from the filesystem, narrows pull
  requests to affected backend implementations, and runs one matrix leg per
  selected backend. Each leg builds that backend's image, starts the compose
  infra plus the backend, and runs the `conformance/` suite against it
  directly. Contract/runtime changes such as `spec/openapi.yaml`,
  `conformance/`, `compose.yaml`, `.dockerignore`, or the Keycloak realm run
  conformance against every backend.
- **E2E** ([`ci.yml`](../.github/workflows/ci.yml)) — the same discovery over
  `frontends/*/Dockerfile` narrows pull requests to affected frontend
  implementations. Each selected frontend gets one composed-stack run
  (backend and gateway pinned to the reference implementations,
  spring-kotlin + yarp), building the images with `scripts/build-images.sh`
  and driving the `e2e/` Playwright suite through the gateway. Frontends have
  no conformance suite of their own, so e2e is their acceptance gate.
  Contract/runtime changes such as `spec/openapi.yaml`, `e2e/`,
  `compose.yaml`, `.dockerignore`, `scripts/build-images.*`, or the
  Keycloak realm run e2e against every frontend. Changes to the e2e reference
  backend (`backends/spring-kotlin`) or gateway (`gateways/yarp`) also run e2e
  against every frontend because those implementations anchor the
  composed-stack suite.
- **`ci-ok`** ([`ci.yml`](../.github/workflows/ci.yml)) — the single merge
  gate: it fails if any selected contract-suite job failed or was cancelled,
  if any observed GitHub Actions check failed, if an implementation directory
  has no `build-<layer>-<name>.yml`, or if a diff-selected `build-*.yml`
  never produced a run for the commit (a missing or broken selected workflow
  creates no checks and must not pass silently). On pushes to `main`,
  scheduled runs, and manual runs it expects every build workflow. Branch
  protection requires `ci-ok` plus CodeQL — required checks never change as
  variants land.
- **Dependency submission** — per-implementation build workflows keep their
  pull-request build jobs at `contents: read`. Workflows for ecosystems where
  CI can resolve a richer build-time graph add a separate
  `*-dependency-submission` job that runs only for trusted pushes to
  `refs/heads/main` and is the only job in that workflow with
  `contents: write` (plus `id-token: write` where the component-detection
  action requires it). Gradle variants run `gradle/actions/setup-gradle` with
  `dependency-graph: generate-and-submit`; Go, Maven, sbt, NuGet, and Cargo
  variants use their ecosystem submission actions. This is intentionally
  separate from GitHub's static dependency graph parsing of committed
  manifests and lockfiles for ecosystems such as npm/Yarn, pip requirements,
  GitHub Actions, Dockerfiles, and compose files: static graph coverage is
  useful, but it is not a CI-submitted Dependency Submission API snapshot.

This keeps PR cost tied to the changed surface without weakening contract
changes: implementation-only changes stay scoped, while contract and shared
runtime changes fan out through the relevant black-box suites. Playwright
reports upload as workflow artifacts when a suite fails.

Each per-implementation build also uploads unit/integration coverage to
[Codecov](https://codecov.io/gh/kamkie/stackverse) under a per-implementation
flag, in whatever report format that toolchain emits (see its build
workflow). `codecov.yml` also mirrors each implementation as a
[component](https://docs.codecov.com/docs/components) — same numbers sliced
yml-side, so PR comments and the dashboard break coverage down per
implementation without extra uploads. Coverage is informational only — see
`codecov.yml` at the repo root; the acceptance gate stays the conformance and
e2e suites. The upload uses `secrets.CODECOV_TOKEN`; for Dependabot-triggered
workflows, GitHub exposes only Dependabot secrets, not regular Actions
secrets, and Codecov requires a token for protected branches. Add a Dependabot
secret with the same `CODECOV_TOKEN` name if Dependabot PRs should publish
coverage. Every Codecov upload step is `continue-on-error: true`, so a missing
token or Codecov outage is visible in the step logs but cannot turn a passing
build, conformance, or e2e job red.

Coverage reports must name source files relative to the repository root before
upload. Some tools run from an implementation directory and emit LCOV entries
such as `SF:src/...`; those paths are ambiguous across Stackverse variants and
do not match the Codecov flag/component path filters. Use
`tools/normalize-coverage-paths.mjs` in the implementation workflow when a
tool emits package-relative LCOV paths or Go module import paths.

Every job — including conformance and e2e — also submits its JUnit test
results to Codecov test analytics under the same flags, even when the tests
fail. The README implementation matrix shows a per-flag coverage badge for
each done implementation.

Two more automations live in `.github/`:

- [`workflows/codeql.yml`](../.github/workflows/codeql.yml) — CodeQL static
  analysis over Kotlin/Java, C#, Go, JS/TS, and the workflow files themselves,
  on every push/PR and weekly. Kotlin needs a real compile, so that matrix leg
  builds every Kotlin project; the rest scan buildless. On `main`, an
  `advanced-security/dismiss-alerts` step honors inline `// codeql[query-id]`
  suppression comments by dismissing the matching code-scanning alerts from each
  language's SARIF. The workflow maps matrix language IDs to CodeQL's actual
  SARIF filenames explicitly, because `java-kotlin` writes `java.sarif` and
  `javascript-typescript` writes `javascript.sarif`.
- [`dependabot.yml`](../.github/dependabot.yml) — weekly dependency PRs for
  every ecosystem (Bundler, Cargo, Composer, Gradle, Go modules, Maven, Mix,
  npm, NuGet, pip, sbt, GitHub Actions, Dockerfiles, and the root plus
  Tracetest compose images), with minor/patch bumps grouped per ecosystem.

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
