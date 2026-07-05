# Selenium WebDriver E2E Showcase

This is an optional testing-tool showcase for Stackverse. It drives a real
running stack through the gateway at `STACKVERSE_URL` and uses the real
Keycloak login form. It does not read tokens from browser storage or inject
authentication state.

The canonical acceptance gates remain:

- `conformance/` for backend API contract behavior
- `e2e/` for the full composed-stack UI contract

This suite is deliberately representative instead of exhaustive. It shows how
plain Selenium WebDriver page objects exercise Stackverse flows:

- login/session through `/auth/login` and Keycloak
- anonymous public feed behavior
- bookmark create/edit/delete
- report submission from the feed
- moderator report actioning
- admin runtime-message management

## Local Run

Start a Stackverse stack first, using dev mode or containers:

```sh
./scripts/run-stack.sh
```

```powershell
./scripts/run-stack.ps1
```

Then run the suite from this directory:

```sh
corepack enable
yarn install --immutable
yarn test
```

```powershell
corepack enable
yarn install --immutable
yarn test
```

`STACKVERSE_URL` defaults to `http://localhost:8000`:

```sh
STACKVERSE_URL=http://localhost:8000 yarn test
```

```powershell
$env:STACKVERSE_URL = "http://localhost:8000"
yarn test
```

## Browser And Driver

The default browser is Chrome in headless mode. Selenium Manager resolves the
matching driver, so a local Chrome/Chromium install is the main prerequisite.

Useful environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `STACKVERSE_URL` | `http://localhost:8000` | Gateway URL for the running stack |
| `SELENIUM_BROWSER` | `chrome` | Browser name passed to Selenium |
| `SELENIUM_HEADLESS` | headless | Set to `false` for a visible browser |
| `SELENIUM_REMOTE_URL` | unset | Use a remote Selenium server/grid |
| `SELENIUM_TIMEOUT_MS` | `15000` | Default explicit wait timeout |

Screenshots for failed scenarios are written to
`testing/selenium-e2e/artifacts/screenshots/`.

## CI

The manual workflow
`.github/workflows/test-selenium-e2e.yml` builds the reference
`spring-kotlin + yarp + react` stack, waits for it at `http://localhost:8000`,
and runs this suite.

It is triggered by `workflow_dispatch` only. That keeps the suite optional and
non-blocking while it is a showcase; `ci-ok` does not treat it as a merge gate.
