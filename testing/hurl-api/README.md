# Hurl API Showcase

This optional showcase suite uses [Hurl](https://hurl.dev/) as executable
plain-text API documentation for representative Stackverse backend flows. It is
not a replacement for the canonical backend gate in `conformance/`.

## Coverage

`stackverse-showcase.hurl` is one ordered scenario that demonstrates:

- public health, public bookmark listing, and message bundle reads
- message ETag revalidation with `304`
- dev-token acquisition through the `stackverse-conformance` Keycloak client
- authenticated bookmark creation, tag listing, and ownership masking
- v1 bookmark deprecation headers and v2 public-feed listing
- report submission, moderator actioning, hidden-bookmark behavior, and restore
- admin user, stats, message CRUD/conflict, and audit-log reads

The scenario creates data with a unique `run_id` supplied by the wrapper
scripts. It deletes the temporary runtime message and private bookmark at the
end. The public bookmark and resolved report are left as normal API data because
they demonstrate the moderation state machine; their titles, URLs, comments, and
message keys are namespaced by `run_id`.

## Target

The suite talks directly to a running backend:

```text
BACKEND_URL=http://localhost:8080
KEYCLOAK_URL=http://localhost:8180
```

It uses the local dev users `demo`, `mentor`, `moderator`, and `admin`, whose
passwords match their usernames. Tokens come from the dev-only
`stackverse-conformance` password-grant client that also powers `conformance/`.

## Local Run

Start the compose infra and any backend implementation first. For example, run
infra from the repo root and start the backend from its implementation
directory:

```sh
docker compose up -d
```

Install Hurl locally, then run from the repo root:

```sh
./scripts/hurl-api.sh
```

```powershell
./scripts/hurl-api.ps1
```

The helpers default `BACKEND_URL`, `KEYCLOAK_URL`, generate `HURL_RUN_ID` when
one is not supplied, and pass extra arguments to Hurl. Useful overrides:

```sh
BACKEND_URL=http://localhost:8080 \
KEYCLOAK_URL=http://localhost:8180 \
HURL_RUN_ID=hurl-local-demo \
./scripts/hurl-api.sh -- --very-verbose
```

```powershell
$env:HURL_RUN_ID = "hurl-local-demo"
./scripts/hurl-api.ps1 --very-verbose
```

Run the Hurl file directly from this directory when you want to experiment with
variables yourself:

```sh
hurl --test \
  --variable backend_url=http://localhost:8080 \
  --variable keycloak_url=http://localhost:8180 \
  --variable run_id=hurl-manual \
  stackverse-showcase.hurl
```

## CI

There is no GitHub Actions workflow for this first Hurl showcase. It is an
optional local comparison suite, so adding a workflow is a separate decision;
if one is added later it should start as `workflow_dispatch` only like the
other non-gating showcase suites.

## Limitations

Hurl is intentionally linear and file-oriented. That makes the scenario easy to
read as HTTP documentation, but it is not where Stackverse proves every semantic
edge case. Pagination stability, full moderation revision rules, localized
validation, blocking, and implementation-wide acceptance remain in
`conformance/`.
