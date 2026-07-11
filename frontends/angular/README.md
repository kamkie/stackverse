# Frontend — Angular

Angular 22 + TypeScript (strict), standalone components, signals, zoneless
change detection, the Angular CLI's esbuild/Vite toolchain. Plain CSS from the
shared design package ([spec/design/](../../spec/design)), bundled verbatim
via `src/styles.css` — this app has no stylesheets of its own. All user-facing
text comes from `GET /api/v1/messages/bundle` at runtime (no i18n library, no
build-time resources); keys the bundle doesn't contain render as their last
segment, so admins can translate them later through the Messages screen
without a deploy.

## Commands

```sh
yarn install
yarn dev              # dev server on :5173, /api and /auth proxied to a gateway on :8000
yarn lint             # angular-eslint: TypeScript, inline/external templates, accessibility
yarn format:check     # Prettier verification (use yarn format to rewrite)
yarn test             # vitest via `ng test` (add --coverage for lcov)
yarn build            # type-checked static production bundle in dist/browser
```

Yarn Berry with the `node-modules` linker. Angular's Vite/esbuild builder
currently loses Yarn PnP virtual package paths, so this implementation keeps
Yarn 4 but installs a generated, gitignored `node_modules` tree. Package
archives still live in the Yarn global cache.

The Angular CLI `lint` target uses angular-eslint's flat configuration,
including inline-template processing and the recommended template
accessibility rules. Prettier owns formatting through the checked-in
`.prettierrc`; both checks run in the component workflow before build/tests.
The single-quote setting follows this Angular CLI project's existing
`.editorconfig`; formatting is intentionally local style under
`docs/INVARIANTS.md` §3, so the Vue sibling keeps its established double quotes.

**No mock mode.** Unlike `frontends/react` (MSW), this app has no in-browser
API mocks: `yarn dev` expects a running gateway on :8000 (see the dev-stack
recipe in [AGENTS.md](../../AGENTS.md) — run this app instead of the React one
in the frontend tab; no `VITE_API_MOCK` equivalent exists here). Mocking is a
per-implementation convenience, not part of the frontend contract.

## Dev action log

In dev the browser console is mirrored to the dev server (`[browser]` lines in
the terminal, `.logs/frontend.log` under dev-stack), and user actions are
logged through the same channel at `DEBUG` (`src/app/dev/log-user-actions.ts`),
so a click can be traced to what it caused without a debugger:

```text
[browser] … DEBUG [action] click button "Add" @ /bookmarks
[browser] … DEBUG [action] click button "Dismiss" in report:123 @ /admin/reports
[browser] … DEBUG [api] POST /api/v1/bookmarks → 201 (38ms)
[browser] … DEBUG [nav] push /bookmarks → /reports
```

The Angular CLI dev server has no middleware hook, so the `/__client-log` sink
is a tiny loopback HTTP server started by `proxy.conf.mjs` (proxy configs are
ES modules evaluated inside the dev-server process) that the dev server
proxies to. Dead clicks are logged too (`(non-interactive)`). The
`in <type>:<id>` part comes from a `data-ctx` attribute on the owning
container — table rows, `BookmarkCard`, and the `Dialog`/`ConfirmDialog`
`ctx` input — so actions repeated per row name their row; tag any new
row/card/dialog the same way. Lines carry element labels, ids, and URLs only —
never field values ([docs/LOGGING.md](../../docs/LOGGING.md) §6/§9) — and none
of this ships in the production bundle (the loader is behind a build-time
`ngDevMode` guard).

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

`src/app/api/types.ts` transcribes the schemas in
[spec/openapi.yaml](../../spec/openapi.yaml) — the spec is the truth; update
the file when the spec changes. (The React sibling generates its types with
openapi-typescript; hand-written interfaces are the trade-off this variant
makes for zero codegen tooling.)

## Production

`yarn build` emits plain static files (`dist/browser`) for the frontend static
server; there is no server-side rendering and no runtime configuration — all
API calls are relative (`/api/...`, `/auth/...`) and carry the session cookie.
Production disables Angular's critical-CSS inlining so the generated document
honors the gateway CSP without `unsafe-inline`.

The `Dockerfile` builds the same bundle into a long-running nginx image
(`stackverse/frontend-angular:local`) that serves the SPA on internal port
8080. `compose.yaml` plugs it in via `FRONTEND_IMAGE` and keeps it behind the
gateway. Build it with the **repo root** as context (it bundles `spec/design`;
see [docs/RUNNING.md](../../docs/RUNNING.md)):

```sh
docker build -t stackverse/frontend-angular:local -f frontends/angular/Dockerfile .
```
