# Axe-Core Accessibility Showcase

This optional browser suite runs axe-core through Playwright against a real
Stackverse stack. It demonstrates automated accessibility checks for
representative frontend states without changing the product contract or
replacing the canonical `e2e/` acceptance suite.

The checks are black-box: they drive the gateway at `STACKVERSE_URL`, use the
real Keycloak login flow for `demo`, `moderator`, and `admin`, and never read or
inject browser tokens. The suite currently scans:

- anonymous public feed with a visible public bookmark
- authenticated **My bookmarks** with a visible private bookmark
- authenticated report dialog
- moderator admin dashboard
- admin messages screen
- admin users screen

## Run Locally

Start a composed stack first, then run the suite from this directory:

```sh
corepack enable
yarn install --immutable
yarn playwright install chromium
yarn test
```

`STACKVERSE_URL` defaults to `http://localhost:8000` and can point at any
running Stackverse gateway:

```sh
STACKVERSE_URL=http://localhost:8000 yarn test
```

## What Failures Mean

Each test name identifies the page or state under scan, and assertion failures
print the axe rule id, impact, help URL, and affected selectors. Playwright also
attaches a `*-axe-violations.json` file to the test result when violations are
found.

The suite is limited to automatically detectable WCAG A/AA rules tagged by
axe-core (`wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`, `wcag22aa`). Passing it
does not prove that a screen is accessible. Keyboard-only navigation, screen
reader behavior, focus order quality, cognitive load, copy clarity, and any
judgment-heavy issue still need manual review.

Treat false positives as review items, not as blanket suppressions. Prefer
fixing markup or shared design tokens. If a rule must be scoped later, document
the exact page state, axe rule, reason, and follow-up issue in this README next
to the code change.

## CI

The GitHub Actions workflow
`.github/workflows/test-axe-a11y.yml` is manual-only (`workflow_dispatch`). It
builds the reference stack (`spring-kotlin` + `yarp` + `react`), runs this suite,
and uploads the Playwright report and JSON/JUnit artifacts on failure. Because
it is a showcase suite, it is not part of the `ci-ok` merge gate.
