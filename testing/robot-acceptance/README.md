# Robot Framework Acceptance Showcase

This optional showcase demonstrates keyword-driven black-box acceptance tests
against Stackverse. It drives a real running stack through the gateway at
`STACKVERSE_URL`, uses the real Keycloak login form, and keeps tokens out of
the browser just like the product does.

The canonical acceptance gates remain:

- `conformance/` for backend API contract behavior
- `e2e/` for the composed-stack UI contract

This suite is deliberately representative instead of exhaustive. It shows how
Robot Framework separates readable acceptance workflows from reusable domain
keywords for:

- login and logout through the gateway and Keycloak
- bookmark creation, editing, and deletion
- report submission from the public feed
- moderator report actioning
- an admin-only backoffice navigation check

## Local Run

Start a real stack first, using dev mode or containers from the repo root:

```sh
./scripts/dev-stack.sh
# or
./scripts/run-stack.sh
```

Then run Robot from this suite directory:

```sh
cd testing/robot-acceptance
python -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
python -m robot --outputdir results tests
```

PowerShell uses the same suite-local command:

```powershell
Set-Location testing/robot-acceptance
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m robot --outputdir results tests
```

`STACKVERSE_URL` defaults to `http://localhost:8000`:

```sh
STACKVERSE_URL=http://localhost:8000 python -m robot --outputdir results tests
```

```powershell
$env:STACKVERSE_URL = "http://localhost:8000"
python -m robot --outputdir results tests
```

## Browser

The default browser is `headlesschrome`, using SeleniumLibrary and Selenium
Manager to resolve the matching driver. A local Chrome/Chromium install is the
main prerequisite.

Useful environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `STACKVERSE_URL` | `http://localhost:8000` | Gateway URL for the running stack |
| `ROBOT_BROWSER` | `headlesschrome` | SeleniumLibrary browser name, such as `chrome` for visible runs |
| `ROBOT_TIMEOUT` | `15 seconds` | Default SeleniumLibrary wait timeout |

## Reports

Robot writes its standard artifacts under `testing/robot-acceptance/results/`:

- `output.xml`
- `log.html`
- `report.html`
- Selenium screenshots captured on failed keywords

Those files are ignored locally and uploaded by the manual workflow when it
runs.

## Robot Tradeoffs

Robot Framework is intentionally different from the code-first Playwright,
Selenium, and Cypress examples. The `.robot` file reads like acceptance
criteria, while `resources/stackverse_keywords.resource` owns the lower-level
browser selectors and workflow mechanics. That makes the suite approachable
for teams that prefer domain keywords, but it also means selector refactors
must keep the keyword layer healthy.

This suite does not duplicate every canonical Playwright end-to-end test. It
uses representative public, authenticated, moderator, and admin flows to show
the tool style while leaving product correctness to `conformance/` and `e2e/`.

## CI

The manual workflow `.github/workflows/test-robot-acceptance.yml` builds the
reference stack (`spring-kotlin` + `yarp` + `react`), starts it through Docker
Compose, runs the suite, and uploads Robot artifacts.

It is triggered by `workflow_dispatch` only. Showcase suites are comparison
material; they do not become merge gates unless the repo explicitly promotes
them.
