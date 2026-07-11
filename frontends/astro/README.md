# Frontend - Astro

Astro 7 generates nine real file-based pages through a shared `.astro` layout and
bundles a SolidJS client entry for their interactive Stackverse content. This boundary is deliberate:
Stackverse is authenticated, runtime-localized, and stateful on every screen, so
there is no useful static page content to render ahead of the gateway session.
Astro still owns the document, asset graph, static output, and development server;
Solid owns the browser state and UI mounted by the external client entry.

The implementation uses strict TypeScript and hand-written API types from the
OpenAPI contract. It imports the shared stylesheets from
[`spec/design`](../../spec/design) verbatim and has no local CSS.

## Commands

```sh
yarn install
yarn dev              # Astro dev server on :5173; proxies /api and /auth to :8000
yarn test             # Vitest unit and component tests
yarn build            # astro check + static production build in dist/
```

Astro 7 and its Vite virtual modules do not support Yarn Plug'n'Play resolution.
This variant therefore uses Yarn Berry's `node-modules` linker, documented in
`.yarnrc.yml`; it remains independently locked with Yarn like its sibling
frontends. There is no mock mode, so development requires a running gateway.

## Architecture

`src/layouts/AppLayout.astro` is the shared CSP-safe document shell. The files in
`src/pages/` map directly to `/feed`, `/bookmarks`, `/reports`, and every `/admin/*`
route, like a conventional Astro or Next.js pages directory. Each generated page
identifies its screen to the external `src/bootstrap.tsx` bundle, which mounts the
corresponding Solid content through `src/App.tsx`. Navigation uses ordinary links;
there is no client router. Nginx serves each generated `index.html`, while its root
fallback remains available for unknown browser routes.

The page-level client application is a deliberate comparison point, not an attempt to hide
the cost: Astro's usual zero-JavaScript static rendering offers little value when every
visible screen depends on `/auth/session`, runtime messages, and user-specific API
data. A server-rendered Astro deployment would also violate this repository's
static-frontend and gateway-owned-session boundaries.

## Dev action log

In development, sanitized browser console output is mirrored to the Astro dev
server. Clicks, submissions, route changes, and same-origin API outcomes are logged
without field values. The forwarding modules are dynamically imported only in
development and are absent from production bundles.

## Logging conformance

Status against the template in [docs/LOGGING.md](../../docs/LOGGING.md) section 10.

| Requirement | Status |
|---|---|
| stdout-only logging | n/a |
| OTLP log export behind `OTEL_SDK_DISABLED` | n/a |
| lifecycle events at `INFO` | n/a |
| expected 4xx not logged as errors | n/a |
| secrets kept out of logs | yes |
| `LOG_LEVEL` honored | n/a |
| trace id on console lines when tracing on | n/a |
| stable `event` names | n/a |
| dependency events | n/a |
| JSON console by default | n/a |
| dev-only console forwarding, sanitized | yes |
| dev-only user-action log, no field values | yes |

## API types

`src/lib/types.ts` transcribes the schemas used by this frontend from
[`spec/openapi.yaml`](../../spec/openapi.yaml). The OpenAPI document remains the
source of truth.

## Production

`yarn build` produces plain static files in `dist/`. All API and auth requests are
relative same-origin paths and use the gateway session cookie.

Build the long-running nginx image with the repository root as context:

```sh
docker build -t stackverse/frontend-astro:local -f frontends/astro/Dockerfile .
```

The image serves port 8080, exposes `/healthz`, and plugs into `compose.yaml` via
`FRONTEND_IMAGE`.
