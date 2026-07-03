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
- **Shared files stay O(1) in the number of implementations** — that is what lets
  parallel variant PRs merge without conflicting. Per-implementation content lives
  in that implementation's directory or its own file: its build/test CI in
  `.github/workflows/build-<layer>-<name>.yml` (the `ci-ok` gate fails when an
  implementation directory lacks its build workflow), its logging-conformance table,
  observability wiring, and image-build command in its README. The shared `ci.yml`
  discovers implementations from the filesystem, and `codecov.yml` /
  `.github/dependabot.yml` pre-seed planned variants. A new variant adds files; the
  only shared edits it should need are its README matrix row and uncommenting its
  pre-seeded config entries.
- **Repo scripts ship in both flavors.** Anything in `scripts/` exists as a `.ps1`
  *and* a `.sh` doing the same thing — add or change them together. Test the shell
  flavor through Git Bash or WSL, not by reading it.
- **Docs follow the change, unprompted.** Any change that touches behavior, scripts,
  env vars, ports, run modes, or contract surface updates every affected document in
  the same change set — README (quickstart, layout, matrix, contract list),
  `docs/RUNNING.md`, `docs/ARCHITECTURE.md`, `docs/LOGGING.md`, `docs/INTENT.md`
  for scope shifts, the component READMEs, and this file. Per-implementation
  surfaces live with the implementation, not in the shared docs: its
  logging-conformance table is in its README (`docs/LOGGING.md` §10 holds only the
  requirement template), its CI in its own `build-*.yml` workflow. Also verify
  cross-references: a document cited for a claim must actually make that claim.
  Waiting to be asked is a defect.
- **Durable repo knowledge belongs in checked-in files.** If an agent discovers a
  reusable Stackverse rule, pitfall, environment requirement, CI/handoff gotcha, or
  tool behavior, record it in this file or the checked-in doc that owns the topic
  as part of the same change set. Do not leave durable repo knowledge only in
  agent-local memory, chat history, transcripts, or machine-local notes; those are
  only for personal preferences, machine-local facts, or pointers back to the
  checked-in instructions.
- **Delegated tasks carry the full delivery flow.** When spawning a background
  task, task chip, Codex session, Claude session, or other agent for Stackverse
  work, include the whole handoff in the prompt: fetch and update `origin/main`
  first, name a fallback base branch if the required code is not on main yet,
  implement the change, run the component's relevant build/tests from its own
  directory, create or rename to an agent-owned `<agent>/<short-task-slug>` branch,
  commit, push, open a PR, run the required cross-review below, triage every
  finding, and report the PR link. Do not spawn agents with prompts that stop at
  "implement and test."
- **A branch task is done only when its PR is up.** Committing locally is not the
  end of the job. Before ending the session or reporting the task complete: rename
  an auto-generated worktree branch to an agent-owned `<agent>/<short-task-slug>`
  branch, push it, open the PR, and run the cross-review below with its findings
  triaged. Work stranded unpushed in a local worktree is an unfinished task — any
  agent that discovers such a branch finishes the handoff (push, PR, cross-review)
  instead of waiting to be asked.
- **Verify spawned-session handoffs.** When a spawned/background agent reports
  completion or a task-ended notification arrives, verify the branch is pushed, a
  PR exists, and the required cross-review comment is present with findings
  triaged. Use `git worktree list` and `gh pr list` as needed; complete missing
  handoff steps immediately instead of only reporting that they are missing.
- **Agent-authored PRs get cross-reviewed.** Before a PR is handed to a human, the
  authoring agent asks the other agent for review, makes sure the review result is
  recorded on the PR, and triages the findings — fix them or answer them on the
  PR. If the review command prints findings but does not post a GitHub comment,
  the authoring agent posts a concise PR comment with the reviewer, findings, and
  triage decision:
  - Claude-authored branch → Codex review: `/codex-cr`, or
    `codex -C <main-repo-root> "review branch <name> ..."` (Codex thread cwd is
    always the main repo root, never a `.claude/worktrees` path; reference the
    branch by name).
  - Codex-authored branch → Claude review: run
    `claude --model fable --permission-mode bypassPermissions -p "/review <PR number>"`
    from the main repo root. If Fable is quota-blocked, rerun with
    `claude --model opus --permission-mode bypassPermissions -p "/review <PR number>"`.
    Do not use Sonnet or Haiku for Stackverse cross-reviews.
  - Commits that implement review findings credit the reviewing agent as
    co-author alongside the authoring agent's own trailer: add
    `Co-Authored-By: Codex <noreply@openai.com>` when fixing Codex findings,
    `Co-Authored-By: Claude <noreply@anthropic.com>` when fixing Claude findings.
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
   Vite proxies `/api` and `/auth` to the gateway). To develop against another
   frontend variant instead, run `yarn dev` in its directory — same port 5173, same
   proxying; whether it has a mock toggle is documented in its README.

Use the app at http://localhost:8000 (gateway). Stop with Ctrl+C per tab and
`docker compose down`.

## Contract conformance tests (`conformance/`)

`./scripts/conformance.ps1` / `./scripts/conformance.sh` run the black-box API
suite in `conformance/` **directly against a backend** — only the compose infra
and one backend need to run, no gateway or frontend. `BACKEND_URL` (default
http://localhost:8080) and `KEYCLOAK_URL` (default http://localhost:8180)
override the targets; per-role tokens come from the dev realm's
`stackverse-conformance` password-grant client. This is the executable form of
`spec/openapi.yaml` + `docs/SPEC.md` and the acceptance gate for every backend
implementation. Tests create uniquely-named data and run serially (moderation
and blocking mutate shared state). The Keycloak client ships in the realm
import; infra created before the client existed needs a one-time
`docker compose up -d --force-recreate keycloak` (dev Keycloak keeps no
volume, so recreating re-imports the realm).

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
