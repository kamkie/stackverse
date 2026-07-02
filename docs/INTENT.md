# Intent

Stackverse exists to answer one question well: **what does the same production-shaped
application look like across different stacks?** One product — a bookmark manager with
a backoffice — implemented repeatedly in different languages, frameworks, and runtimes,
by one author, under one contract and one set of conventions.

It serves three audiences:

1. **Developers comparing stacks.** Because the spec, architecture, conventions, and
   design are held constant, a diff between two implementations shows the stacks
   themselves — not differences in author skill, scope interpretation, or taste.
2. **Mentees looking for solved examples.** "How do I structure a Spring Boot service?",
   "how does session auth work with a BFF?", "what does a moderation workflow look
   like?" — each implementation is a complete, working, consistent answer.
3. **Readers of the accompanying articles.** The repository is the executable
   companion to a planned series of write-ups comparing the implementations.

The architectural thesis threaded through everything: **stateless applications with
the session owned at the edge** (BFF / token-handler pattern) — tokens never reach
the browser, any instance can serve any request.

## Goals

- **Equal-footing comparison.** Same functional spec ([SPEC.md](SPEC.md)), same API
  contract ([spec/openapi.yaml](../spec/openapi.yaml)), same architecture
  ([ARCHITECTURE.md](ARCHITECTURE.md)), same environment variables, same visual
  design across frontends. Anything not deliberately compared is held constant.
- **Mix and match.** Any frontend behind any gateway in front of any backend works —
  one compose file, pick the combination with two environment variables.
- **Production-shaped, small scope.** The feature set is compact but exercises what
  real services need: OIDC, hierarchical RBAC, ownership rules, validation,
  pagination, runtime-managed i18n, ETag caching, API versioning with a live
  deprecation (v1 → v2), a moderation state machine, app-level account state,
  an audit trail, and aggregate reporting. Small enough to finish in every stack;
  real enough to be worth reading.
- **Contract-first.** The spec is the product. Implementations conform to it;
  none of them defines it.
- **Idiomatic per stack, consistent across stacks.** Each implementation should look
  like what an experienced developer in that ecosystem would write — while exposing
  identical behavior. Where idiom and consistency conflict, behavioral consistency
  wins and the tension itself is documented as comparison material.
- **Teachable.** Code is written to be read: complete, self-contained
  implementations that a mentee can study end to end.

## Non-goals

- **Not a starter template or framework.** Nothing here is meant to be forked as the
  seed of your product. There is deliberately no shared library between
  implementations — duplication across stacks is the point.
- **Not a benchmark shootout.** The comparison is qualitative — code shape, ergonomics,
  toolchain, operational surface. No performance numbers, no "winner". Benchmarks
  need methodology this repo does not attempt.
- **Not exhaustive stack coverage.** Implementations are added when they carry
  comparison or teaching value, not to collect every framework. Quality and
  consistency over quantity — this is the deliberate difference from RealWorld's
  community-contributed matrix.
- **Not a growing product.** The feature scope is frozen by the spec; new features
  are assumed rejected by default. A small spec fully implemented everywhere beats
  a large spec implemented somewhere. (Registration, notifications, impersonation,
  import/export, feature flags, rate limiting, multi-tenancy — see the out-of-scope
  list in [SPEC.md](SPEC.md).)
- **Not an identity-management demo.** Keycloak is infrastructure here. User
  registration, password policy, and federation are its concern, exercised through
  its own console — the interesting boundary is how applications *consume* identity.
- **Not a deployment showcase.** Local docker compose is the only supported runtime.
  Kubernetes manifests, cloud IaC, and CI/CD pipelines per stack would multiply the
  maintenance surface without adding comparison value on the axes this repo cares
  about.
- **Not demonstrating tokens-in-the-browser.** The SPA-holds-a-JWT pattern is
  explicitly what this repository argues against; no implementation will offer it,
  even as an option.
