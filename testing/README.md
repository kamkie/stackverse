# Testing-Tool Showcase Suites

This directory is for optional suites that compare testing tools against the
same Stackverse product. They are first-class examples, but they are not the
canonical acceptance gates.

## Gates vs showcases

| Suite | Purpose | Target | Gate status |
|---|---|---|---|
| `conformance/` | API contract and backend semantics from `spec/openapi.yaml` and `docs/SPEC.md` | direct backend, `BACKEND_URL` + `KEYCLOAK_URL` | required for every backend |
| `e2e/` | Required browser screens and gateway/session behavior | composed stack, `STACKVERSE_URL` | required for every frontend |
| `testing/schemathesis-api` | Generated OpenAPI property tests for edge cases and response/schema checks | direct backend, `BACKEND_URL` + `KEYCLOAK_URL` | manual showcase |
| `testing/hurl-api` | Plain-text executable HTTP scenarios for representative API flows | direct backend, `BACKEND_URL` + `KEYCLOAK_URL` | local showcase |
| `testing/<tool>-<scope>` | Tool comparison through representative flows | backend or composed stack, depending on scope | showcase unless deliberately promoted |
| `testing/selenium-e2e` | Selenium WebDriver page-object browser showcase | composed stack, `STACKVERSE_URL` | optional manual workflow |
| `testing/cypress-e2e` | Cypress browser-runner showcase for representative session, feed, CRUD, and moderation flows | composed stack, `STACKVERSE_URL` | manual showcase workflow |
| `testing/robot-acceptance` | Robot Framework keyword-driven acceptance showcase for representative UI workflows | composed stack, `STACKVERSE_URL` | manual showcase workflow |
| `testing/axe-a11y` | Axe-core automated accessibility checks for representative browser states | composed stack, `STACKVERSE_URL` | manual showcase workflow |
| `testing/zap-security` | OWASP ZAP passive baseline security smoke scan | composed stack gateway, `STACKVERSE_URL` | manual showcase workflow |
| `testing/tracetest-otel` | Tracetest assertions that one gateway API action produces one trace spanning gateway and backend spans | composed stack with observability enabled | manual showcase workflow |

Showcase suites should demonstrate the tool, not clone every canonical test.
Pick flows that exercise the tool's strengths while staying anchored to the
existing product contract: login/session, public feed, bookmark CRUD, report
submission, moderation, admin workflows, API edge cases, accessibility checks,
light system smoke, security baseline, or observability assertions.

## Naming

Use `testing/<tool>-<scope>`, where `tool` is the lowercase tool or ecosystem
name and `scope` says what it drives. Examples:

- `testing/selenium-e2e`
- `testing/cypress-e2e`
- `testing/schemathesis-api`
- `testing/hurl-api`
- `testing/robot-acceptance`
- `testing/postman-api`
- `testing/bruno-api`
- `testing/k6-system`
- `testing/zap-security`
- `testing/axe-a11y`
- `testing/tracetest-otel`

## Suite shape

Each suite owns its dependencies and commands. A typical directory looks like:

```text
testing/<tool>-<scope>/
  README.md
  package.json | pyproject.toml | requirements.txt | ...
  tests/ | collection/ | scripts/ | ...
```

The suite README must document:

- what the suite demonstrates and why it is representative
- whether it targets `STACKVERSE_URL` or `BACKEND_URL` plus `KEYCLOAK_URL`
- local prerequisites and the default command from the suite directory
- how it gets dev credentials or drives the real Keycloak login
- what artifacts it writes, such as reports, traces, screenshots, or logs
- whether CI runs it, and whether that CI is gating or optional
- known tool limitations, expected findings, or suppressions

Prefer one obvious local command (`yarn test`, `npm test`, `pytest`, `hurl
--test`, `newman run`, etc.) from inside the suite directory. If a root-level
helper script is needed, add both `.sh` and `.ps1` forms with equivalent
behavior, matching the repository script rule.

## Runtime contract

Browser-level suites run against the gateway with:

```text
STACKVERSE_URL=http://localhost:8000
```

They must use the real gateway and Keycloak login flow. They must not read or
inject browser tokens; Stackverse deliberately keeps tokens out of the browser.

Direct API suites run against a backend with:

```text
BACKEND_URL=http://localhost:8080
KEYCLOAK_URL=http://localhost:8180
```

They may use the dev realm's documented users or the
`stackverse-conformance` client to obtain tokens, but they must not require
secrets beyond the checked-in local-dev credentials.

Suites that mutate data should create unique names and clean up when the tool
makes that practical. Flows that touch shared state, such as moderation and
user blocking, should run serially or isolate their data so repeated local and
CI runs remain deterministic.

## CI pattern

When a showcase suite needs CI, add a sibling workflow:

```text
.github/workflows/test-<tool>-<scope>.yml
```

The workflow should install only that suite's toolchain, start the minimum
required Stackverse runtime, run the suite's documented command, and upload
useful artifacts on failure.

Important: `ci-ok` waits for every GitHub Actions check run on a commit. That
means any showcase workflow triggered by `push` or `pull_request` is part of
the effective merge gate unless it reports success, neutral, or skipped when
the showcase fails. Keep early suites on `workflow_dispatch`, `schedule`, or a
failure-tolerant job until the repo deliberately promotes them.

Promotion from showcase to gate requires an explicit docs and CI change that
states why the suite now defines correctness. Until then, `conformance/` and
`e2e/` remain the canonical acceptance path.
