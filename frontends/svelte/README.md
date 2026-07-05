# Frontend — Svelte

Svelte 5 + TypeScript (strict), Vite, and hand-written API types from the
OpenAPI contract. Plain CSS from the shared design package
([spec/design/](../../spec/design)) is imported verbatim; this app has no
stylesheets of its own. All user-facing text comes from
`GET /api/v1/messages/bundle` at runtime, with missing keys rendered as their
last segment so admins can fill translations through the Messages screen.

## Commands

```sh
yarn install
yarn dev              # dev server on :5173, /api and /auth proxied to a gateway on :8000
yarn test             # vitest unit tests
yarn build            # static production bundle in dist/
```

Yarn Berry with Plug'n'Play — there is no `node_modules`; resolution goes
through `.pnp.cjs` and packages live in the global cache. On Windows,
`svelte-check` can hit Yarn PnP path-casing issues when the global cache is on
another drive; `yarn build` uses the Vite/Svelte compiler path that works
locally and in CI. Like the Angular frontend, this implementation has no mock
mode: run it against a real gateway or through the root dev-stack recipe and
replace the frontend tab with `yarn dev` in this directory.

## Dev action log

In dev the browser console is mirrored to the Vite dev server
(`[browser]` lines in the terminal, `.logs/frontend.log` under dev-stack), and
user actions are logged through the same channel at `DEBUG`
(`src/dev/logUserActions.ts`): clicks, form submits, route changes, and
same-origin `/api`/`/auth` request outcomes. Lines carry element labels, ids,
and URLs only, never field values, and this code is absent from production
bundles.

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

## Production

`yarn build` emits plain static files (`dist/`) for the frontend static
server; there is no server-side rendering and no runtime configuration. All
API calls are relative (`/api/...`, `/auth/...`) and carry the gateway session
cookie.

The `Dockerfile` builds the same bundle into `/dist` in a long-running nginx
image (`stackverse/frontend-svelte:local`) that serves the SPA on internal
port 8080. `compose.yaml` plugs it in via `FRONTEND_IMAGE` and keeps it behind
the gateway. Build it with the **repo root** as context:

```sh
docker build -t stackverse/frontend-svelte:local -f frontends/svelte/Dockerfile .
```
