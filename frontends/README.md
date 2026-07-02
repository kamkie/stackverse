# Frontends

One directory per implementation (`react`, `angular`, ...). The frontend is a SPA that
consumes [spec/openapi.yaml](../spec/openapi.yaml) *through the gateway* and holds
**no authentication state** — not even a token. The session cookie is invisible to it.

## Shared design

All frontends must render the same design — same layout, colors, and spacing — so
that the only thing left to compare is the framework code (the TodoMVC approach:
a shared stylesheet, not a per-stack reinvention). The shared design lives in
[spec/design/](../spec/design) and is part of the contract:

- [spec/design/tokens.css](../spec/design/tokens.css) — design tokens as `--sv-*`
  CSS custom properties: colors (light + `prefers-color-scheme: dark`), typography,
  spacing, radii, shadows, layout dimensions.
- [spec/design/stackverse.css](../spec/design/stackverse.css) — framework-agnostic,
  `sv-`prefixed component classes (buttons, forms, cards, tags, badges, tables,
  dialog, dashboard stats/chart, states).

Rules:

1. Load both files **verbatim** — reference or bundle them from `spec/design/`
   directly (or copy byte-identical at build time); never fork or edit a copy.
2. Build markup out of the `sv-*` classes. If something an implementation needs
   is not styled yet, extend `spec/design/` — app-local stylesheets are not
   allowed. A change to `spec/design/` is a contract change: it must keep every
   existing frontend rendering correctly.
3. **No CSS frameworks or UI kits** (Tailwind, Material, Bootstrap, ...) — they
   would make implementations differ in exactly the dimension this repo holds
   constant.
4. Dark mode follows `prefers-color-scheme` by default and can be forced via
   `data-theme="light" | "dark"` on `<html>`. Every frontend provides a theme
   switcher (auto / light / dark), persists the choice client-side (e.g.
   `localStorage` key `stackverse.theme`), and applies it before first paint.

## Contract with the gateway

- All API calls go to relative `/api/v{n}/...` paths with `credentials: 'include'`.
  Where an operation exists in two versions, use the newest: bookmark lists come from
  the cursor-paginated `GET /api/v2/bookmarks` ("load more" UX via `nextCursor`),
  everything else from v1.
- Current-user state comes from `GET /auth/session` on startup
  (`{"authenticated":true,"username":...}` or `{"authenticated":false}`).
- "Log in" is a plain navigation to `/auth/login` (full page redirect — it's an OIDC
  flow, not an XHR). "Log out" is a `POST /auth/logout` followed by a state reset.
- State-changing `/api/*` calls (`POST`/`PUT`/`PATCH`/`DELETE`) must carry the CSRF
  header: read the `XSRF-TOKEN` cookie (deliberately not `HttpOnly`) and echo its
  value as `X-XSRF-TOKEN`. A `403` problem document means the header was missing or
  didn't match — re-read the cookie and retry.
- A `401` from `/api/*` means the session died; treat it as logged-out and offer login.

## Internationalization

All user-facing text comes from `GET /api/v1/messages/bundle` — no hardcoded strings, no
build-time resource bundles. Load the bundle on startup and on language switch, and
send `If-None-Match` so unchanged bundles cost a `304`. The user's language choice is
passed as `?lang=` and persisted client-side (e.g. `localStorage`); with no stored
choice, omit `lang` and let `Accept-Language` do its job. The seed keys in
[spec/messages/en.json](../spec/messages/en.json) are guaranteed to exist.

## Required screens

1. **My bookmarks** — cursor-paginated list ("load more"), filter by tag and text
   search, create/edit/delete; hidden bookmarks show a moderation flag.
2. **Public feed** — anonymous view of public bookmarks (`?visibility=public`), with a
   report action when authenticated.
3. **Tag sidebar/cloud** — from `GET /api/v1/tags`, click to filter.
4. **Login/session UI** — login button when anonymous, username + logout when not.
5. **Language switcher** — at least `en`/`pl`, reloads the bundle without a page reload.

### Admin section (`/admin`, role-gated from `GET /api/v1/me`)

Navigation shows only what the caller's roles allow — `moderator` sees the dashboard
and reports; `admin` sees everything:

6. **Dashboard** (`moderator`) — totals from `GET /api/v1/admin/stats` plus a chart of
   the 30-day series.
7. **Reports queue** (`moderator`) — open reports with dismiss/action buttons; actioned
   reports hide the bookmark.
8. **Users** (`admin`) — searchable directory with block/unblock (reason required to block).
9. **Audit log** (`admin`) — filterable, paginated browser.
10. **Messages** (`admin`) — list/create/edit/delete localized messages.

Validation errors (RFC 9457 problem documents with an `errors` array) must be surfaced
on the corresponding form fields, not as a generic toast.

## Conventions

- Generate or hand-write API types from the OpenAPI spec — but the spec is the truth.
- Production build must be servable as static files by any gateway (`GATEWAY` serves
  the bundle); dev mode runs its own server which gateways can proxy via `FRONTEND_URL`.
- Per-implementation README covers stack-specific tooling only.
