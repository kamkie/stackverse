# Frontend — Lit (Web Components)

Lit 3 with TypeScript and Vite: a light-DOM custom element hosts the SPA, while
routing, form state, validation display, runtime i18n, and ETag-aware bundle
fetching stay in local modules. Components intentionally render into light DOM
so the shared global stylesheet remains authoritative. Plain CSS comes from the
shared design package ([spec/design/](../../spec/design)), imported verbatim —
this app has no stylesheets of its own.

## Commands

```sh
yarn install
yarn dev       # dev server on :5173, /api and /auth proxied to a gateway on :8000
yarn test      # vitest unit tests
yarn build     # type-check + static production bundle in dist/
```

Yarn Berry with Plug'n'Play — there is no `node_modules`; resolution goes
through `.pnp.cjs` and packages live in the global cache. Vite prints the same
PnP deprecation warning as the other frontends; if a future toolchain release
breaks PnP, switch `.yarnrc.yml` to `nodeLinker: node-modules`.

**No mock mode.** This implementation expects a running gateway on :8000 in dev
mode. Mocking is a per-implementation convenience, not part of the frontend
contract.

## Deliberate deviations & notes

- **Light DOM over shadow DOM.** Lit defaults to shadow roots, but Stackverse's
  shared design is a global stylesheet that every frontend consumes verbatim.
  This variant renders into light DOM so `spec/design/*.css` remains the single
  source of visual truth.
- **Route-level template helpers.** The app keeps a single custom-element shell
  plus local route/form template helpers instead of one custom element per card,
  dialog, and table row. That keeps the cross-stack markup easy to compare and
  avoids duplicating the shared design inside shadow roots.
- **Hand-written API types.** Generated OpenAPI types are common in larger Lit
  apps. This variant keeps the frontend self-contained by transcribing the small
  Stackverse contract into `src/types.ts`; the OpenAPI spec remains canonical.
- **No formatter/linter toolchain.** As with several other frontend siblings,
  strict TypeScript plus the build/test workflow is the static gate for now.

## Dev action log

In dev the browser console is mirrored to the dev server (`[browser]` lines in
the terminal, `.logs/frontend.log` under dev-stack), and user actions are
logged through the same channel at `DEBUG` (`src/dev/logUserActions.ts`):

```text
[browser] … DEBUG [action] click button "Add" @ /bookmarks
[browser] … DEBUG [action] click button "Dismiss" in report:123 @ /admin/reports
[browser] … DEBUG [api] POST /api/v1/bookmarks -> 201 (38ms)
[browser] … DEBUG [nav] push /bookmarks -> /reports
```

Dead clicks are logged as `(non-interactive)`. The `in <type>:<id>` part comes
from `data-ctx` on cards, rows, and dialogs. Lines carry labels, ids, and URLs
only — never field values ([docs/LOGGING.md](../../docs/LOGGING.md) §6/§9) —
and none of this ships in the production bundle.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) §10;
`❌` rows are this implementation's agreed, visible backlog.

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

`src/types.ts` transcribes the schemas in
[spec/openapi.yaml](../../spec/openapi.yaml). The spec is the truth; update the
file when the spec changes. This keeps the local, platform-oriented character
of the variant visible without introducing shared code generation.

## Production

`yarn build` emits plain static files (`dist/`) for the frontend static server;
there is no server-side rendering and no runtime configuration — all API calls
are relative (`/api/...`, `/auth/...`) and carry the session cookie.

The `Dockerfile` builds the same bundle into a long-running nginx image
(`stackverse/frontend-lit:local`) that serves the SPA on internal port 8080.
`compose.yaml` plugs it in via `FRONTEND_IMAGE` and keeps it behind the
gateway. Build it with the **repo root** as context (it bundles `spec/design`;
see [docs/RUNNING.md](../../docs/RUNNING.md)):

```sh
docker build -t stackverse/frontend-lit:local -f frontends/lit/Dockerfile .
```
