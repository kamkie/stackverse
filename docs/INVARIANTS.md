# Invariants

Stackverse is one product implemented in many stacks. This document draws the line
between **what must be identical in every implementation** and **what is free to be
idiomatic per stack** — the distinction that decides whether a difference between two
variants is a *contract breach*, a *documented deliberate deviation*, or *just style*.

This file is an **index, not a second source of truth**: each invariant below names
the canonical document that defines it normatively. When they disagree, the canonical
document wins and this file is the bug. Do not restate rules here in a way that could
drift — link and summarize.

## Three tiers

1. **Invariants (§1).** Identical in every implementation. A variant that breaks one
   is broken, not idiomatic. Changing an invariant is a deliberate, repo-wide contract
   decision that obligates updating *every* implementation — never a local shortcut
   (see [AGENTS.md](../AGENTS.md) ground rules 1 and 4).
2. **Framework-idiom expectations (§2).** Each stack should use the foundational
   pillars that are the reason to choose it, and by established repo convention a
   departure is called out as a deliberate deviation in the variant's own README.
   This is a convention, not part of the hard §1 contract.
3. **Free to vary (§3).** Naming, formatting, and internal layout beyond the above.
   Not a finding, not something to justify.

The tiers map directly to how a reviewer should weigh a difference: a §1 breach is a
defect; a §2 departure is worth raising only when undocumented; a §3 difference is never
a defect.

## 1. Invariants — identical in every implementation

### Contract & behavior
- **Wire API** — endpoints, paths, request/response shapes, and status codes are
  identical across backends and are defined by
  [spec/openapi.yaml](../spec/openapi.yaml) and [docs/SPEC.md](SPEC.md). The contract
  is executable: [conformance/](../conformance) is the acceptance suite for it.
- **Errors** — failures are `application/problem+json` (RFC 9457) with the shapes the
  spec defines; the same condition yields the same status code in every stack.
- **API versioning is the backend's concern** — `/api/v1`, `/api/v2`, and later coexist
  behind the same gateway route; the gateway is version-agnostic.

### Architecture (the thesis)
- **Stateless application, session at the edge, tokens never in the browser.** The app
  processes hold no session; the gateway owns the session (in Redis) and relays tokens;
  the only state the browser holds is the session cookie. Full statement in
  [docs/ARCHITECTURE.md](ARCHITECTURE.md).
- **Gateway contract (public port 8000)** — every gateway exposes the same surface:
  `/auth/login`, `/auth/callback` (a failed callback creates no session and still
  redirects to `/` — never a 5xx/error page), `/auth/logout` → `204`, `/auth/session`,
  `/api/**` (token-relay reverse proxy; anonymous relay when logged-out), and `/**`
  (the frontend web application). The session cookie (`stackverse_session`,
  `HttpOnly`, `SameSite=Lax`, `Secure` outside local dev), the token-refresh
  semantics (IdP **rejects** → destroy
  session, degrade to anonymous; IdP **unavailable** → keep session, fail with `503`),
  the CSRF double-submit check (`XSRF-TOKEN` cookie / `X-XSRF-TOKEN` header, mismatch →
  `403 problem+json`), the same-origin enforcement (no CORS; `Origin`/`Sec-Fetch-Site`
  checks against `PUBLIC_URL`), and the browser security-response-header set are all
  part of that contract and identical across gateways. Normative table in
  [docs/ARCHITECTURE.md § The gateway contract](ARCHITECTURE.md#the-gateway-contract).
- **The gateway adds nothing to API semantics** — no body rewriting, no auth decisions,
  no rewrite of `Cache-Control`/`ETag`/`Content-Language`/`304` on proxied `/api/**`. A
  `401` the frontend sees is the backend's problem document passed through untouched.
- **The backend is stateless and authorizes per endpoint** — it validates the JWT
  (issuer, audience, expiry, and signature via JWKS — see
  [backends/README.md](../backends/README.md)) and owns which endpoints require which
  role; its only state is the database.

### Configuration
- **Environment-variable names are identical across stacks** — e.g. `OIDC_ISSUER_URI`,
  `OIDC_JWKS_URI`, `BACKEND_URL`, `PUBLIC_URL`, `FRONTEND_URL`, and the `OTEL_*` set
  (`OTEL_SERVICE_NAME`, `OTEL_SDK_DISABLED`, …). The per-layer variable lists are the
  canonical reference: [backends/README.md](../backends/README.md) and
  [gateways/README.md](../gateways/README.md).
- **Ports (local dev) are fixed** — gateway 8000, backend 8080, Keycloak 8180,
  PostgreSQL 5432, Redis 6379, frontend dev server 5173. Table in
  [docs/ARCHITECTURE.md § Ports](ARCHITECTURE.md#ports-local-dev).

### Cross-cutting
- **Logging is a cross-implementation contract** — the event vocabulary, severity
  rules, required fields, and the "what must never be logged" list are normative and
  identical across stacks. Canonical: [docs/LOGGING.md](LOGGING.md).
- **Observability shape is identical** — standard `OTEL_*` config, silent by default
  (`OTEL_SDK_DISABLED=true`), and one-browser-action-is-one-trace (the gateway
  propagates W3C `traceparent` to the backend). See
  [docs/ARCHITECTURE.md § Observability](ARCHITECTURE.md#observability).
- **No shared code between implementations** — no common libraries, no shared schema,
  no cross-imports. Duplication across stacks is the point of the repo
  ([AGENTS.md](../AGENTS.md) ground rule 2).
- **Acceptance gates are the same for everyone** — [conformance/](../conformance)
  (per backend) and [e2e/](../e2e) (whole stack); the `ci-ok` check is the single
  required gate.

## 2. Framework-idiom expectations (deviations allowed, but documented)

Choosing a framework is a promise to use it the way it is meant to be used. A stack
should adopt its **foundational pillars** — for example:

- **Persistence:** the stack's idiomatic data layer (Spring Data JPA, Jakarta
  Persistence/JPA, GORM for Grails, Hibernate ORM/Panache for Quarkus, Micronaut Data,
  EF Core, SQLx, …) rather than hand-rolled JDBC/SQL.
- **Dependency injection / component model:** the framework's container (Spring, CDI,
  Micronaut, NestJS providers, FastAPI `Depends`) rather than hand-wired singletons.
- **Security:** the framework's auth module (Spring Security, `micronaut-security`,
  MicroProfile JWT / Jakarta Security, SmallRye JWT) rather than a bespoke token filter.
- **Gateways:** the platform's proxy model (YARP routes/transforms, Spring Cloud
  Gateway route DSL + filters, nginx `proxy_pass`, `@fastify/http-proxy`).

For an educational polyglot repo, deviating from a pillar can be a legitimate teaching
choice (e.g. plain SQL kept visible for cross-stack comparison). The repo's established
convention — every backend README already does this — is to call such a departure out in
that variant's own README, in a *Deliberate deviations* section (the exact heading varies
across variants — e.g. *Deliberate deviations worth comparing* or *Deliberate deviations &
notes*), with the reason. An unremarked bypass reads as an oversight rather than a
demonstration. This §2 expectation is a convention, not part of the non-negotiable §1
contract: it follows from [AGENTS.md](../AGENTS.md) ground rule 3 (idiomatic style within
a stack is encouraged) and the per-implementation-README practice.

Note that using the idiomatic-for-the-stack approach for something an invariant does not
pin — the persistence layer, DI style, error-mapping mechanism, validation mechanism,
project structure — is **not** a deviation and needs no justification. §1 fixes the
observable contract, not the internal machinery.

For the full per-stack catalog of these conventions — what idiomatic looks like for each
language/framework and where each variant follows or departs — see
[CONVENTIONS.md](CONVENTIONS.md).

## 3. Free to vary

Naming, formatting, import order, file and package layout beyond what §1 and §2 imply,
and any other purely local choice. Idiomatic style *within* a stack is encouraged.
Purely cosmetic layout differences are not deviations or findings. When structure
materially bypasses a framework's conventional component or feature boundaries,
however, [CONVENTIONS.md](CONVENTIONS.md) may record it as a §2 idiom deviation;
documenting that choice makes it deliberate rather than a contract defect.

## Using this document

- **Implementers:** uphold every §1 invariant exactly; adopt your stack's §2 pillars or
  record the departure in your README's deliberate-deviations section; §3 is yours.
- **Reviewers (human or agent):** a §1 breach is a defect; a §2 departure is worth
  raising only when it is undocumented; a §3 difference is not a finding. Judge a variant
  against *its own* stack's idioms, never against another stack's — cross-stack sameness
  is required only for §1.
