# Stackverse — agent instructions

Stackverse is a contract-first polyglot demo: one product (a bookmark manager)
implemented in many stacks. Read these before changing anything:

- [docs/INTENT.md](docs/INTENT.md) — why the repo exists; goals and non-goals (canonical for scope decisions)
- [docs/SPEC.md](docs/SPEC.md) — functional rules (canonical for behavior)
- [spec/openapi.yaml](spec/openapi.yaml) — API shapes and status codes (canonical for the wire)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — BFF/session-auth architecture, gateway route contract, ports

## Ground rules

1. **The contract is the product.** Never change `spec/openapi.yaml` or `docs/SPEC.md`
   to make one implementation's life easier. A contract change is a deliberate,
   separate decision that obligates updating *every* implementation.
2. **Implementations are siblings, not a framework.** No shared code between
   implementations — no common libraries, no shared schema, no cross-imports.
   Duplication across stacks is the point of the repository.
3. **Consistency across stacks matters more than local cleverness.** Same endpoints,
   same env vars (see `backends/README.md`, `gateways/README.md`), same behavior.
   Idiomatic style *within* a stack is encouraged; contract drift is not.
4. **Stateless applications, session at the gateway.** Do not introduce in-process
   sessions, sticky-session assumptions, or tokens in the browser. That is the
   architectural thesis of the whole repo.
5. **Scope discipline.** New features must land in `docs/SPEC.md` first and are
   assumed rejected by default — the demo's value is a small, fully-implemented spec.

## Working here

- Work inside one implementation directory at a time; each has its own build and
  toolchain. Run builds/tests from that directory, not the repo root.
- Update the implementation matrix in `README.md` when an implementation changes status.
- `compose.yaml` at the root runs infra (`docker compose up -d`) and pluggable
  app combos (`--profile app` with `BACKEND_IMAGE`/`GATEWAY_IMAGE`).
