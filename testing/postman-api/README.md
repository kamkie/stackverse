# Postman API Showcase

This suite demonstrates Stackverse API workflows as a Postman collection. It
is a showcase for collection-oriented API exploration and command-line runs;
the canonical backend gate remains `conformance/`.

## Coverage

The collection talks directly to a backend and covers representative flows:

- anonymous public bookmark feeds, API versioning headers, and message bundle
  ETag revalidation
- dev-realm token acquisition for regular, moderator, and admin users
- authenticated bookmark creation, ownership masking, v1/v2 listing, and tag
  counts
- report submission, duplicate-report conflict, moderator queue resolution,
  hide/restore, and hidden-bookmark masking
- admin stats, user directory, runtime-message CRUD, and audit-log visibility

It intentionally does not duplicate every semantic conformance case from
`conformance/`.

## Target

The suite talks directly to a backend:

```text
BACKEND_URL=http://localhost:8080
KEYCLOAK_URL=http://localhost:8180
```

It uses the dev realm's `stackverse-conformance` password-grant client to get
bearer tokens for `demo`, `mentor`, `moderator`, and `admin`. Passwords are the
documented local-dev values, matching the usernames. Tokens are generated into
the active Postman/Newman environment at run time and are not checked in.

## Local Run With Newman

Start the compose infra plus any backend implementation first, then run from
this suite directory:

```sh
corepack enable
yarn install --immutable
yarn test
```

```powershell
corepack enable
yarn install --immutable
yarn test
```

Defaults are `BACKEND_URL=http://localhost:8080` and
`KEYCLOAK_URL=http://localhost:8180`. Override them with normal environment
variables:

```sh
BACKEND_URL=http://localhost:8080 KEYCLOAK_URL=http://localhost:8180 yarn test
```

```powershell
$env:BACKEND_URL = "http://localhost:8080"
$env:KEYCLOAK_URL = "http://localhost:8180"
yarn test
```

The Newman runner writes CLI output plus JSON and JUnit reports under
`testing/postman-api/newman-report/`.

## Local Run With Postman CLI

The same files can be run with the Postman CLI:

```sh
postman collection run stackverse-api-showcase.postman_collection.json \
  --environment stackverse-local.postman_environment.json \
  --env-var "BACKEND_URL=http://localhost:8080" \
  --env-var "KEYCLOAK_URL=http://localhost:8180"
```

Or through the suite helper:

```sh
yarn test:postman-cli
```

The helper uses the same `BACKEND_URL` and `KEYCLOAK_URL` environment
variables as the Newman runner.

## CI

`.github/workflows/test-postman-api.yml` is manual (`workflow_dispatch`) so the
collection stays optional and non-blocking. The workflow builds one selected
backend image, starts the minimum runtime (`postgres`, `keycloak`, and that
backend), runs `yarn test` here, and uploads the Newman reports.

Promote it to a required gate only with an explicit docs and CI change that
explains why the Postman collection now defines correctness alongside
`conformance/`.

## Repeatability

The first request generates a per-run id and tag, so created bookmarks,
reports, and runtime messages do not collide with previous runs. The cleanup
folder deletes the created bookmarks and runtime message; resolved reports and
audit entries may remain as normal append-only product data.
