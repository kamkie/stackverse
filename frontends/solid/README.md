# Frontend - SolidJS

SolidJS + TypeScript (strict), Vite, and hand-written API types from the
OpenAPI contract. Plain CSS from the shared design package
([spec/design/](../../spec/design)) is imported verbatim; this app has no
stylesheets of its own. All user-facing text comes from
`GET /api/v1/messages/bundle` at runtime, with missing keys rendered as their
last segment so admins can fill translations through the Messages screen.

## Commands

```sh
yarn install
yarn dev              # dev server on :5173, /api and /auth proxied to a gateway on :8000
yarn test             # Vitest helper and Solid Testing Library component/page tests
yarn test --coverage  # same suite with LCOV under coverage/
yarn build            # typecheck + static production bundle in dist/
```

Yarn Berry with Plug'n'Play - there is no `node_modules`; resolution goes
through `.pnp.cjs` and packages live in the global cache. Like the Svelte
frontend, this implementation has no mock mode: run it against a real gateway
or through the root dev-stack recipe and replace the frontend tab with
`yarn dev` in this directory.

## Dev action log

In dev the browser console is mirrored to the Vite dev server
(`[browser]` lines in the terminal, `.logs/frontend.log` under dev-stack), and
user actions are logged through the same channel at `DEBUG`
(`src/dev/logUserActions.ts`): clicks, form submits, route changes, and
same-origin `/api`/`/auth` request outcomes. Lines carry element labels, ids,
and pathnames only (query strings and field values are omitted), and this code
is absent from production bundles.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10.

| Requirement | Status |
|---|---|
| stdout-only logging | n/a |
| OTLP log export behind `OTEL_SDK_DISABLED` | n/a |
| lifecycle events at `INFO` | n/a |
| expected 4xx not logged as errors | n/a |
| secrets kept out of logs | ✅ |
| `LOG_LEVEL` honored | n/a |
| trace id on console lines when tracing on | n/a |
| stable `event` names (§5: lifecycle, session, security, moderation) | n/a |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | n/a |
| JSON console by default (`LOG_FORMAT`) | n/a |
| dev-only console forwarding, sanitized | ✅ |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | ✅ |

## API types are hand-written from the contract

`src/lib/types.ts` transcribes the schemas used by the frontend from
[spec/openapi.yaml](../../spec/openapi.yaml). The spec remains the source of
truth; update this file when the contract changes.

## Deliberate deviations worth comparing

Solid's idiomatic surface here is Solid components in TSX, fine-grained
signals for local and shared state, and a Vite SPA entry point. Routing is a
small History API signal instead of a router package because the app has a
small fixed route table and the shared e2e suite drives route behavior at the
browser boundary.

API types are hand-written rather than generated from OpenAPI to match the
lightweight sibling implementations that keep the wire shapes visible in one
file. Vitest covers reusable helpers and contract edges (CSRF, i18n, session,
routing), while Solid Testing Library exercises representative forms,
presentation components, and public/admin pages. The shared Playwright e2e
suite remains the full browser acceptance gate. There is no ESLint/Prettier
setup yet, so `yarn build`/`yarn test` are the enforced local gates.

## Production

`yarn build` emits plain static files (`dist/`) for the frontend static
server; there is no server-side rendering and no runtime configuration. All
API calls are relative (`/api/...`, `/auth/...`) and carry the gateway session
cookie.

The `Dockerfile` builds the same bundle into `/dist` in a long-running nginx
image (`stackverse/frontend-solid:local`) that serves the SPA on internal
port 8080. `compose.yaml` plugs it in via `FRONTEND_IMAGE` and keeps it behind
the gateway. Build it with the **repo root** as context:

```sh
docker build -t stackverse/frontend-solid:local -f frontends/solid/Dockerfile .
```
