# Cypress E2E Showcase

This suite demonstrates Cypress against representative Stackverse browser
flows. It is a showcase, not the canonical acceptance gate: the full contract
still lives in `conformance/` and `e2e/`.

## Coverage

The suite runs against a composed stack at `STACKVERSE_URL` and covers:

- real gateway-to-Keycloak login and logout session behavior
- anonymous public feed rendering
- bookmark create, edit, and delete through the UI
- a moderator backoffice flow that dismisses an open report

It intentionally does not duplicate every Playwright spec. The goal is to show
what Cypress feels like on Stackverse while staying anchored to the same public,
authenticated, and backoffice surfaces.

## Local Run

Start a real stack first, using dev mode or containers from the repo root:

```sh
./scripts/dev-stack.sh
# or
./scripts/run-stack.sh
```

Then run Cypress from this suite directory:

```sh
cd testing/cypress-e2e
yarn install --immutable
yarn test
```

PowerShell uses the same suite-local commands:

```powershell
Set-Location testing/cypress-e2e
yarn install --immutable
yarn test
```

Defaults:

- `STACKVERSE_URL=http://localhost:8000`
- `KEYCLOAK_ORIGIN=http://localhost:8180`

Set `STACKVERSE_URL` when the gateway is elsewhere. Set
`KEYCLOAK_ORIGIN` when that gateway redirects to a non-default Keycloak
origin; Cypress needs the IdP origin for `cy.origin()`.

For interactive debugging:

```sh
yarn test:open
```

`yarn install` normally installs the Cypress binary. If a local Yarn policy has
disabled package build scripts and `yarn verify` reports that Cypress is
missing, run `yarn cypress install` once, then rerun `yarn verify`.

## Authentication Model

The tests drive the same `/auth/login` top-level navigation a user does, fill
the real Keycloak form, and let the gateway callback create the session. Cypress
`cy.session()` caches the resulting gateway cookies per dev user, but the tests
never read, inject, or store browser tokens.

Test setup mutations use same-origin gateway API calls with the browser's
session cookie and the `XSRF-TOKEN` cookie echoed as `X-XSRF-TOKEN`, matching
the SPA contract.

## Cypress Tradeoffs

Cypress gives a tight local debugging loop, command log, screenshots, videos,
automatic retrying, and useful `cy.session()` support for repeated login flows.
Those strengths make this suite readable as a browser-workflow showcase.

The main tradeoff for Stackverse is OIDC. Because Keycloak is a different
origin from the gateway, the login helper must enter a `cy.origin()` block and
must know the Keycloak origin. Playwright's context model makes that particular
cross-origin flow less explicit. Cypress also runs one browser context per spec
flow, so it is less natural for multi-user scenarios than the canonical
Playwright suite, which opens separate contexts for setup and moderation.

## CI

The manual workflow `.github/workflows/test-cypress-e2e.yml` builds the
reference stack (`spring-kotlin` + `yarp` + `react`), starts it through Docker
Compose, runs `yarn test` here, and uploads Cypress screenshots/videos on
failure.

It is deliberately `workflow_dispatch` only. Showcase suites are comparison
material; they do not become merge gates unless the repo explicitly promotes
them.
