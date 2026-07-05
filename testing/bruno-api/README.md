# Bruno API Showcase

This showcase suite is a curated Bruno collection for representative
Stackverse backend API workflows. It is not a replacement for the canonical
backend gate in `conformance/`; it is an API-client comparison artifact that
is useful for exploration, demos, and reviewing request examples in Git.

## Coverage

The collection runs directly against a backend:

```text
BACKEND_URL=http://localhost:8080
KEYCLOAK_URL=http://localhost:8180
```

It covers:

- public health, readiness, bookmark feed, and message-bundle reads
- dev-realm token acquisition through the `stackverse-conformance` client
- authenticated `demo` identity, bookmark creation, owner reads, tag listing,
  and cleanup
- `mentor` report submission plus `moderator` report queue and dismissal
- `admin` stats, runtime-message create/list/delete, and audit-log reads

The requests create unique names from a runtime `runId`. The final cleanup
request deletes the created bookmark, and the admin flow deletes its runtime
message. If a run is interrupted, leftover data is namespaced by that run id.

## Local Run

Prerequisites are Node.js, the Bruno CLI dependencies installed from this
directory, and a running compose infra plus any backend implementation.

```sh
cd testing/bruno-api
npm install
npm test
```

PowerShell uses the same suite-local commands:

```powershell
Set-Location testing/bruno-api
npm install
npm test
```

`npm test` runs:

```sh
bru run --env-file environments/local.yml --bail
```

The checked-in `environments/local.yml` defaults to
`BACKEND_URL=http://localhost:8080` and `KEYCLOAK_URL=http://localhost:8180`.
Override them through Bruno environment variables when needed:

```sh
npm exec -- bru run --env-file environments/local.yml --bail \
  --env-var BACKEND_URL=http://localhost:8080 \
  --env-var KEYCLOAK_URL=http://localhost:8180
```

```powershell
npm exec -- bru run --env-file environments/local.yml --bail `
  --env-var BACKEND_URL=http://localhost:8080 `
  --env-var KEYCLOAK_URL=http://localhost:8180
```

## Authentication

The `01 - Auth Tokens` folder documents and executes token acquisition for the
local dev realm. Each request posts to:

```text
{{KEYCLOAK_URL}}/realms/stackverse/protocol/openid-connect/token
```

with form fields:

```text
grant_type=password
client_id=stackverse-conformance
username=<demo|mentor|moderator|admin>
password=<same as username>
```

The token responses are stored only as Bruno runtime variables
(`demoToken`, `mentorToken`, `moderatorToken`, `adminToken`) for the current
collection run. They are not written to the repository or to the checked-in
environment file.

## Bruno Format

The suite uses Bruno's OpenCollection YAML format:

- `opencollection.yml` is the collection root.
- `environments/local.yml` holds non-secret local defaults.
- Each request is a small `.yml` file with request data and tests next to it.

This keeps the collection reviewable in ordinary pull requests and avoids
hidden workspace state. The collection can also be opened in the Bruno desktop
app by selecting `testing/bruno-api` as the collection directory.

## Tooling Fit

Bruno overlaps with Postman as a request collection, environment, scripting,
and collection-runner tool. The contrast is storage and collaboration: this
suite is plain checked-in OpenCollection YAML, so there is no cloud workspace
or exported JSON blob to keep in sync.

Bruno overlaps with Hurl as a Git-friendly, CLI-runnable API workflow. The
contrast is interaction style: Hurl is compact and assertion-first, while
Bruno is more exploratory, with a GUI tree, environments, request docs,
runtime variables, and generated reports.

## CI

There is no GitHub Actions workflow for this showcase yet. Keeping it local
avoids turning an exploratory API-client suite into an accidental merge gate;
`conformance/` remains the required backend API acceptance suite.

## Limitations

The current `@usebruno/cli` dependency tree reports npm audit findings in
dev-only transitive packages. `npm audit --omit=dev` is clean because this
suite ships no production runtime dependency. Do not run `npm audit fix
--force` blindly here; npm currently suggests a breaking CLI downgrade.
