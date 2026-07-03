# Stackverse Bookmarks — functional spec

Every implementation builds the same product: a small bookmark manager with a
backoffice. The scope is deliberately compact but exercises the things real services
need: authentication, hierarchical role-based authorization, ownership, validation,
a many-to-many relation, pagination, filtering, internationalization with
server-managed messages, HTTP caching with ETag revalidation, a moderation workflow
with a state machine, app-level account state next to IdP identity, an append-only
audit trail, aggregate reporting, and a public (anonymous) surface next to an
authenticated one.

The API contract lives in [spec/openapi.yaml](../spec/openapi.yaml). This document
describes the behavior behind it. Where the two disagree, the OpenAPI spec wins for
shapes and status codes; this document wins for business rules.

## Domain

**Bookmark**
- `id` — server-generated UUID
- `url` — required, must be a valid absolute http(s) URL, max 2000 chars
- `title` — required, 1–200 chars
- `notes` — optional, max 4000 chars
- `tags` — 0–10 lowercase slugs (`[a-z0-9-]{1,30}`); stored normalized (trimmed, lowercased, deduplicated)
- `visibility` — `private` (default) or `public`
- `status` — `active` (default) or `hidden`; server-managed, set only through moderation
- `owner` — username of the creator, immutable
- `createdAt` / `updatedAt` — server-managed, RFC 3339 UTC

**Report** (a user flagging a public bookmark for moderation)
- `id` — server-generated UUID
- `bookmarkId` — the reported bookmark
- `reporter` — username, immutable
- `reason` — `spam`, `offensive`, `broken-link`, or `other`
- `comment` — optional, max 1000 chars
- `status` — `open` (initial), `dismissed`, or `actioned`
- `resolvedBy` / `resolvedAt` / `resolutionNote` — set on resolution, max 1000 chars for the note
- `createdAt` — server-managed
- at most one `open` report per `(bookmarkId, reporter)`; violation → `409`

**User account** (app-level record; identity itself belongs to Keycloak)
- `username` — from the JWT, primary key
- `firstSeen` / `lastSeen` — server-managed
- `status` — `active` (default) or `blocked`
- `blockedReason` — set while blocked, max 1000 chars
- `bookmarkCount` — derived, returned in admin views

**Audit entry** (append-only; never updated or deleted)
- `id` — server-generated UUID
- `actor` — username of the moderator/admin
- `action` — dot-separated verb, e.g. `message.created`, `report.resolved`, `user.blocked`, `bookmark.status-changed`
- `targetType` / `targetId` — what was acted on
- `detail` — optional JSON snapshot of the change
- `createdAt` — server-managed

**Message** (localized UI/validation text, managed at runtime — not resource bundles baked into the build)
- `id` — server-generated UUID
- `key` — required, dot-separated lowercase segments (`^[a-z0-9-]+(\.[a-z0-9-]+)*$`), max 150 chars
- `language` — required, two-letter lowercase ISO 639-1 code
- `text` — required, 1–2000 chars
- `description` — optional context for translators, max 1000 chars
- `createdAt` / `updatedAt` — server-managed, RFC 3339 UTC
- unique constraint on `(key, language)`; violation → `409`

## Roles

Hierarchical roles, carried in the JWT's `realm_access.roles` claim. In Keycloak,
`admin` is a *composite* realm role that includes `moderator`, so an admin token
carries both role strings — backends check for the single role an endpoint needs
and never re-implement the hierarchy themselves.

- *(no role)* — regular user: full bookmark features, read-only messages, can report public bookmarks
- `moderator` — additionally: reports queue, report resolution, bookmark hide/restore, dashboard stats
- `admin` (includes `moderator`) — additionally: message management, user directory and blocking, audit log

A valid token without the required role → `403` problem document (unlike private
bookmarks, the existence of backoffice resources is not a secret, so no 404 masking).

## API versioning

The API is URI-versioned (`/api/v{n}/...`) and evolves **per operation**: a new major
version of an operation is introduced only for a breaking change, coexists with its
predecessor, and the predecessor keeps working while signaling deprecation with
`Deprecation` (RFC 9745), `Sunset` (RFC 8594), and `Link rel="successor-version"`
(RFC 8288) headers. The gateway is version-agnostic — it proxies `/api/**` as-is;
version routing is entirely the backend's concern.

The repository ships one such migration as a **permanent exhibit** (it is never
actually sunset — it exists to be studied):

- `GET /api/v1/bookmarks` — offset pagination (`page`/`size`/`totalItems`), deprecated;
  every response carries the three deprecation headers.
- `GET /api/v2/bookmarks` — same filters and semantics, keyset (cursor) pagination:
  `cursor` + `size` in, `{items, nextCursor}` out. Cursors are opaque strings clients
  must never parse or construct; pagination is stable under concurrent inserts (no
  skips, no duplicates between pages); `nextCursor` is absent on the last page;
  a malformed or unresolvable cursor → `400`.

Both versions are served by the same underlying logic — v2 is a different
representation, not a different feature. All other operations exist in v1 only.

## Rules

1. **Ownership.** Users see and manage their own bookmarks. Reading a specific bookmark
   succeeds for the owner always, and for others only when it is `public`. Updating and
   deleting are owner-only. A non-owner accessing a private bookmark gets `404`
   (not `403` — existence is not disclosed).
2. **Public surface.** The bookmark listings with `visibility=public` (v1 and v2) work
   without authentication and return only public bookmarks (any owner). Every other
   endpoint requires authentication.
3. **Listing.** The bookmark listings return the caller's own bookmarks by default,
   newest first, and support `tag` (exact match, repeatable = AND) and `q`
   (case-insensitive substring over title and notes). Page size defaults to 20,
   max 100. v1 paginates by offset, v2 by cursor — see *API versioning*.
4. **Tags.** `GET /api/v1/tags` returns the caller's tags with usage counts, most used first.
5. **Validation.** Invalid input → `400` with an RFC 9457 problem document listing field
   errors. Unknown JSON fields are ignored.
6. **Identity.** The backend derives the user from the validated JWT (`preferred_username`
   claim) and roles from `realm_access.roles`, never from request data. `GET /api/v1/me`
   echoes the caller's identity including roles.
7. **Messages.** Reads (`GET /api/v1/messages`, `/api/v1/messages/bundle`,
   `/api/v1/messages/{id}`) are public. Writes require the `admin` role.
   `GET /api/v1/messages` supports exact `key` and `language` filters, a `q`
   filter (case-insensitive substring over key and text), plus the standard
   pagination.
8. **Language resolution.** For any localized response the language is resolved as:
   explicit `lang` query parameter → first supported language in `Accept-Language`
   (quality-ordered) → default `en`. Unsupported values fall back down the chain, never
   error. "Supported" means: at least one message exists in that language.
9. **Bundle.** `GET /api/v1/messages/bundle` returns a flat `key → text` object for the
   resolved language; keys missing in that language fall back to their `en` text.
   The response carries a `Content-Language` header with the language actually served.
10. **Caching.** All message reads return an `ETag` and `Cache-Control: no-cache`;
    a request with a matching `If-None-Match` returns `304` with an empty body.
    Any message write must change the ETags of affected reads.
11. **Localized validation.** Field errors in problem documents carry a `messageKey`
    (from the `validation.*` namespace) and a `message` localized per rule 8 —
    looked up from the messages table, falling back to `en`.
12. **Seed.** On startup, backends import [spec/messages/*.json](../spec/messages)
    (language = filename) idempotently: only `(key, language)` pairs that don't exist
    yet are inserted, so runtime edits by admins survive restarts. The seed files are
    part of the contract — frontends may rely on those keys existing.
13. **Reporting.** Any authenticated user can report a bookmark that is visible to
    them and public. Reporting a private or hidden bookmark → `404` (rule 1 masking
    applies). A second `open` report by the same user on the same bookmark → `409`;
    a new report is allowed once the previous one is resolved or withdrawn.
    Reporters keep sight of their filings: `GET /api/v1/reports` lists the caller's
    own reports (newest first, optional `status` filter, standard pagination),
    including the resolution fields once moderation acts. While a report is `open`
    its reporter may revise `reason`/`comment` (`PUT /api/v1/reports/{id}`) or
    withdraw it (`DELETE` → `204`; the report is removed and no longer blocks a new
    one on the same bookmark). Both act only on the caller's own reports: another
    user's report → `404` (existence is not disclosed), a non-`open` report → `409`.
14. **Resolution.** Moderators resolve `open` reports as `dismissed` (no effect) or
    `actioned` (the bookmark becomes `hidden`). Resolving an `actioned` report
    auto-resolves every other `open` report on the same bookmark as `actioned`, with
    the same resolver and note. Resolving a non-`open` report → `409`.
15. **Hidden bookmarks.** Excluded from the public feed and from non-owner reads
    (`404`). The owner still sees them in their own list (with `status: hidden`),
    may edit fields, but any update while hidden that sets `visibility: public` →
    `409`. Moderators hide and restore via `PUT /api/v1/admin/bookmarks/{id}/status`;
    restoring sets `status: active` and leaves `visibility` untouched.
16. **Lazy provisioning.** On every authenticated request the backend upserts the
    caller's user account row (`firstSeen` on first sight, `lastSeen` always).
    No sync job, no Keycloak admin API — the JWT is the only source.
17. **Blocking.** Admins block/unblock users with a reason. A blocked user
    authenticates fine (identity is Keycloak's business) but every authenticated
    endpoint returns `403` with a problem document stating the account is blocked;
    the anonymous public surface keeps working. Admins cannot block themselves
    (`409`). Blocking takes effect on the next request — no session revocation.
18. **Audit.** Every mutation through a moderator/admin capability (message writes,
    report resolutions, bookmark status changes, user blocking) writes an audit
    entry. Entries are immutable; there is no API to change or delete them.
    `GET /api/v1/admin/audit-log` filters by actor, action, target and time range.
19. **Stats.** `GET /api/v1/admin/stats` returns totals (users, bookmarks, public and
    hidden counts, open reports), a daily series for the last 30 days including
    today (`bookmarksCreated`, `activeUsers` = users whose `lastSeen` falls on that
    day; days without data are zero-filled), and the top 10 tags by usage across
    all users. ETag semantics as in rule 10.

## Acceptance checklist

An implementation is **done** when:

- [ ] All operations in `spec/openapi.yaml` are implemented with the semantics above
- [ ] Schema migrations run automatically on startup (Flyway, EF migrations, goose, ...)
- [ ] Message seed import runs on startup and is idempotent
- [ ] Role checks are enforced from `realm_access.roles` (`moderator` vs `admin`); `403` without the required role
- [ ] Message reads and stats support ETag / `If-None-Match` / `304` revalidation
- [ ] Validation messages are served in `en` and `pl` per the language resolution rules
- [ ] Report workflow works end to end, including the reporter's own
      list/edit/withdraw surface (rule 13) and sibling auto-resolution (rule 14)
- [ ] Hidden bookmarks disappear from the public surface and cannot be re-published by the owner
- [ ] User accounts are lazily provisioned; blocked users get `403` on authenticated calls
- [ ] Every backoffice mutation produces an immutable audit entry
- [ ] Stats series is zero-filled over the last 30 days
- [ ] v1 and v2 bookmark listings coexist over the same logic; v1 responses carry
      `Deprecation`, `Sunset`, and successor `Link` headers; v2 cursors are opaque
      and stable under concurrent inserts
- [ ] The service is stateless — two instances behind the gateway work without affinity
- [ ] JWT validation is done against the IdP's JWKS (no shared secrets with the gateway)
- [ ] `/healthz` (liveness) and `/readyz` (readiness, checks DB) respond correctly
- [ ] A `Dockerfile` builds the service; the image runs in `compose.yaml` via `BACKEND_IMAGE`
- [ ] Configuration comes exclusively from environment variables (see [backends/README.md](../backends/README.md))
- [ ] The shared conformance suite ([conformance/](../conformance)) passes against
      the running service: `./scripts/conformance.sh` / `.ps1` (see [RUNNING.md](RUNNING.md))

## Out of scope (on purpose)

User registration and profile management (Keycloak owns users), password handling,
email and notifications, admin impersonation, import/export, feature flags, rate
limiting, multi-tenancy, sharing/collaboration features. Scope creep is the enemy
of a comparison project.
