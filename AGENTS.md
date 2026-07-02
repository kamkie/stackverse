# Stackverse — agent instructions

Stackverse is a contract-first polyglot demo: one product (a bookmark manager)
implemented in many stacks. Read these before changing anything:

- [docs/INTENT.md](docs/INTENT.md) — why the repo exists; goals and non-goals (canonical for scope decisions)
- [docs/SPEC.md](docs/SPEC.md) — functional rules (canonical for behavior)
- [spec/openapi.yaml](spec/openapi.yaml) — API shapes and status codes (canonical for the wire)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — BFF/session-auth architecture, gateway route contract, ports
- [docs/LOGGING.md](docs/LOGGING.md) — logging requirements, normative for every implementation

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
- **Repo scripts ship in both flavors.** Anything in `scripts/` exists as a `.ps1`
  *and* a `.sh` doing the same thing — add or change them together. Test the shell
  flavor through Git Bash or WSL, not by reading it.
- **Docs follow the change, unprompted.** Any change that touches behavior, scripts,
  env vars, ports, run modes, or contract surface updates every affected document in
  the same change set — README (quickstart, layout, matrix, contract list),
  `docs/RUNNING.md`, `docs/ARCHITECTURE.md`, `docs/LOGGING.md` (incl. its
  conformance table), `docs/INTENT.md` for scope shifts, the component READMEs, and
  this file. Also verify cross-references: a document cited for a claim must
  actually make that claim. Waiting to be asked is a defect.
- Update the implementation matrix in `README.md` when an implementation changes status.
- `compose.yaml` at the root runs infra (`docker compose up -d`) and pluggable
  app combos (`--profile app` with `BACKEND_IMAGE`/`GATEWAY_IMAGE`).

## Full dev mode (each module in a visible terminal)

`./scripts/dev-stack.ps1` (Windows) starts the Docker infra, waits for Keycloak,
and opens one Windows Terminal tab per module. Each tab tees its output to
`.logs/<module>.log` at the repo root (gitignored), so humans watch the terminals
and agents read the files — check there before asking for log output.

What the script runs (also the manual recipe):

1. `docker compose up -d` from the repo root; wait for Keycloak to report healthy.
2. Backend — in `backends/spring-kotlin`: `./gradlew bootRun` (defaults match the compose infra).
3. Gateway — in `gateways/yarp`: `dotnet run --project src/StackverseGateway` with
   `FRONTEND_URL=http://localhost:5173` so it proxies the frontend dev server.
4. Frontend — in `frontends/react`: `yarn dev` with `VITE_API_MOCK=false` (mocks off,
   Vite proxies `/api` and `/auth` to the gateway).

Use the app at http://localhost:8000 (gateway). Stop with Ctrl+C per tab and
`docker compose down`.

## End-to-end tests (`e2e/`)

`./scripts/e2e.ps1` / `./scripts/e2e.sh` run the Playwright suite against a
**running stack** (dev mode or containers; `STACKVERSE_URL` overrides the
default http://localhost:8000). The suite is black-box and contract-level: it
logs in through the real Keycloak form (`demo`/`moderator`/`admin`), exercises
every required screen from `frontends/README.md`, and works against any
backend + gateway + frontend combination. Tests create uniquely-named data and
run serially (`workers: 1`) because moderation actions mutate shared state.

### Windows Terminal pitfalls (launching tabs programmatically)

Use `wt -w <window-name> new-tab --title <t> ...` — one invocation per tab; the named
window is created on first use and reused by later calls, so no `;` chaining is needed.
Two silent failure modes with inline commands:

1. Windows Terminal splits its command line on unescaped `;` **even inside quoted
   arguments** and treats the remainder as another wt subcommand (error 0x80070002,
   "cannot find the file"). Escape as `\;` or avoid semicolons.
2. Do not "fix" that with `&&`: PowerShell parses `$env:X='y' && cmd` as assigning the
   *entire pipeline chain's output* to `$env:X`, so `cmd` runs with the variable
   **unset** and no error is raised (the gateway silently serves its placeholder page,
   the frontend silently keeps mocks on).

Instead of inline commands, write a small launcher `.ps1` per module (set env vars,
`Set-Location`, run the process) and start each tab with `pwsh -NoExit -File <script>`.
When cleaning up mislaunched processes by matching `CommandLine`, exclude the current
shell (`$_.ProcessId -ne $PID`) — the filter string appears in your own command line.
