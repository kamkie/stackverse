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
yarn dev              # dev server on :5173, /api and /auth proxied to a gateway on :8000
yarn lint             # ESLint with Lit, Web Components, and TypeScript rules
yarn format:check     # verify Prettier formatting
yarn test             # Vitest helper and Open WC fixture-based component tests
yarn build            # type-check + static production bundle in dist/
yarn check            # lint + format + typecheck + tests + production build
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
- **Focused light-DOM modules.** `main.ts` only composes the shared CSS and app
  element. State, navigation, view primitives, bookmark pages/cards, admin pages,
  dialogs, and the Lit shell/controller live in focused modules. They retain
  delegated actions and light-DOM markup so the shared stylesheet stays byte-for-byte
  authoritative without turning every small template into a custom element.
- **Hand-written API types.** Generated OpenAPI types are common in larger Lit
  apps. This variant keeps the frontend self-contained by transcribing the small
  Stackverse contract into `src/types.ts`; the OpenAPI spec remains canonical.
- **Canonical tooling.** ESLint applies the Lit and Web Components recommended
  rules alongside TypeScript, Prettier enforces formatting, and Open WC's testing
  helpers mount the real custom element in light DOM.

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

| Requirement                                                                | Status |
| -------------------------------------------------------------------------- | ------ |
| stdout-only logging                                                        | n/a    |
| OTLP log export behind `OTEL_SDK_DISABLED`                                 | n/a    |
| lifecycle events at `INFO`                                                 | n/a    |
| expected 4xx not logged as errors                                          | n/a    |
| secrets kept out of logs                                                   | ✅     |
| `LOG_LEVEL` honored                                                        | n/a    |
| trace id on console lines when tracing on                                  | n/a    |
| stable `event` names (§5: lifecycle, session, security, moderation)        | n/a    |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`)        | n/a    |
| JSON console by default (`LOG_FORMAT`)                                     | n/a    |
| dev-only console forwarding, sanitized                                     | ✅     |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | ✅     |

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
