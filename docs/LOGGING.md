# Logging requirements

Normative for every implementation — backends and gateways in full, frontends
where noted (§9). Logging is a shared convention like the environment
variables in `backends/README.md`: any idiomatic library is fine, but the
observable behavior below must be identical across stacks. MUST / SHOULD /
MAY are used in the RFC 2119 sense. Grounding: OWASP Logging and Logging
Vocabulary cheat sheets, NIST SP 800-92, the CNCF observability whitepaper
(references at the end).

Two boundaries up front:

- **Logs are diagnostics, not the audit trail.** The append-only audit trail
  (`docs/SPEC.md` rule 16) is a product feature in the database with its own
  API and its own integrity guarantees. Moderation and admin actions MAY log
  a diagnostic line, but the audit entry is the authoritative record — do not
  build a second audit system in the log pipeline.
- **The log platform is the operator's problem, but its requirements are
  written down.** Centralization, encryption, retention, and access control
  live in Appendix A: out of scope for this repo's dev-grade Loki
  (`docs/INTENT.md`), binding for anyone deploying a stack for real. Sections
  1–9 make the *emitting side* production-ready.

## 0. Policy at a glance

The application emits structured JSON logs to stdout/stderr. Each entry
carries an RFC 3339 UTC timestamp, severity, a stable event name for
contract events, a human-readable message, an outcome, and the relevant
actor/resource identifiers — plus trace and span IDs whenever a trace
context exists. Service name, version, and instance travel as OpenTelemetry
resource attributes, not as hand-written per-line fields. Machine-queryable
values are structured fields, never only prose. The application logs
lifecycle, session/authentication, security-signal, moderation (diagnostic —
the audit trail stays authoritative), dependency, and error events; it never
logs credentials, tokens, cookies, session identifiers, or un-sanitized
client input. The platform side — central collection, trace correlation,
access control, in-flight redaction, retention, alerting — is Appendix A.

## 1. Where logs go

- Applications MUST write logs to **stdout/stderr only** (12-factor). No log
  files, no rotation, no in-process shipping — the platform owns capture.
  (Dev mode tees terminals to `.logs/<module>.log` via `scripts/dev-stack.*`;
  that is the harness's doing, not the application's.)
- When telemetry is enabled (`OTEL_SDK_DISABLED=false`), applications MUST
  also export log records over OTLP using the standard `OTEL_*` variables, so
  they land in Loki next to the Tempo traces (see `docs/RUNNING.md`).
- Service identity (`service.name`, `service.version`, instance id) MUST be
  carried as **OpenTelemetry resource attributes** on exported records —
  never repeated by hand in every line. Console output covers one service per
  container; duplicating resource fields per line bloats logs and can drift
  from the exporter's truth. A deployment MAY add
  `deployment.environment.name` via `OTEL_RESOURCE_ATTRIBUTES`; the
  application itself has no notion of named environments.
- Logging failures MUST NOT crash or block the application. A dead log
  pipeline is an alert (Appendix A), not an outage multiplier.

## 2. Event shape

- One event per line. Multi-line payloads (stack traces excepted) MUST be
  encoded, not emitted raw — a crafted input must never forge a log line (§6).
- Every event MUST carry: a UTC timestamp in RFC 3339 format with at least
  millisecond precision (`2026-07-02T15:20:00.123Z`), severity level,
  logger/category, and a human-readable message kept separate from the
  structured fields.
- Events for anything enumerated in §5 MUST additionally carry a stable,
  machine-readable **`event` name** from the vocabulary table and an
  **`outcome`** (`success`, `failure`, `denied`, `timeout`). Failures SHOULD
  carry a stable `error_code` rather than free text alone.
- **Console format:** structured JSON is the default and REQUIRED in any
  production deployment; `LOG_FORMAT=text` (§8) opts into human-readable
  output for local development. Machine-queryable values MUST be emitted as
  structured fields — never only embedded in the message body, where they
  are lost to regex archaeology.
- When a trace context exists, exported records MUST carry `trace_id` and
  `span_id`, and console lines SHOULD include the trace id — the link into
  Grafana is what makes a line actionable.
- A `schema_version` field MAY be added; the OTel logs data model already
  versions the envelope, so it is not required.

## 3. Severity levels

| Level | Meaning | In production |
|---|---|---|
| `FATAL` | the process cannot continue; logged once, exit non-zero | on |
| `ERROR` | unexpected failure, someone should look; always with stack trace | on |
| `WARN` | degraded or suspicious, self-healing | on |
| `INFO` | lifecycle and meaningful events | on (default level) |
| `DEBUG` | developer detail | off; enabling it must be safe per §6 — verbosity may cost performance, never confidentiality |

- The default level MUST be `INFO`.
- Expected client behavior is **not** an application error: validation
  failures, `401`, `403`, `404`, and CSRF rejections MUST NOT log above
  `INFO`. They are *security signals* (OWASP), not stack-trace material —
  spikes belong to metrics/alerting, not to ERROR noise.
- `5xx` responses MUST log at `ERROR` with the stack trace and trace id.

## 4. Required fields

| Field | Required | Notes |
|---|---|---|
| `timestamp` | yes | UTC, ≥ millisecond precision |
| `level` | yes | §3 |
| `message` | yes | human-readable summary |
| `event` | for §5 events | stable machine-readable name, part of the contract |
| `outcome` | for §5 events | `success` / `failure` / `denied` / `timeout` |
| `error_code` | for failures | stable code over free text |
| `trace_id`, `span_id` | when tracing | inherited from context, never invented |
| `actor` | when applicable | `preferred_username` — the repo's identity; never a token |
| `resource_type`, `resource_id` | when applicable | e.g. `bookmark`/UUID; never the payload |
| `duration_ms` | for dependency calls | latency diagnosis |
| `service.*` resource attrs | on OTLP export | §1 — not per-line by hand |

## 5. Event vocabulary

Stable `event` names, identical across implementations. This is the generic
OWASP vocabulary mapped onto what Stackverse actually does — note that
password logins, MFA, and password resets happen **inside Keycloak**, which
keeps its own event log; the application side logs only what it can see.

**Lifecycle — all components, `INFO` (`FATAL` on refusal to start):**

| `event` | when |
|---|---|
| `application_start` | listening; include effective config with secrets redacted |
| `application_stop` | signal received / shutdown complete — restarts distinguishable from crashes |
| `db_migration_applied` | per migration, backends only |
| `message_seed_imported` | idempotent seed result, backends only |

**Session and authentication — gateway, plus backend where noted:**

| `event` | level | when |
|---|---|---|
| `oidc_callback_completed` | `INFO` | code flow finished; outcome success/failure |
| `session_created` | `INFO` | ticket stored, cookie issued |
| `session_destroyed` | `INFO` | logout or refresh failure; include reason |
| `token_refresh_failed` | `WARN` | the IdP *rejected* the refresh (`400`/`401`): session destroyed, request degraded to anonymous. An *unavailable* IdP (unreachable, `5xx`, `429`) is `dependency_call_failed` instead — the session is kept (docs/ARCHITECTURE.md) |
| `idp_logout_failed` | `WARN` | backchannel revocation failed (best-effort by design) |
| `jwt_validation_failed` | `INFO` | backend rejected a bearer token (expired/invalid) |
| `blocked_user_rejected` | `WARN` | backend refused a blocked account |

**Security signals — where they occur, `INFO` unless noted:**

| `event` | when |
|---|---|
| `csrf_validation_failed` | gateway 403 on state-changing `/api` request |
| `authz_denied` | valid token without the required role (403) |
| `input_validation_failed` | RFC 9457 validation problem returned |

**Moderation and admin — backend, `INFO`, diagnostic only (audit trail is
the authority):** `report_created`, `report_updated`, `report_withdrawn`,
`report_resolved`, `report_reopened`, `user_blocked`, `user_unblocked`,
`bookmark_status_changed`, `message_created`, `message_updated`,
`message_deleted`. (`report_updated` and `report_withdrawn` are reporter
self-service actions — they log but write no audit entry; SPEC rule 18
covers moderator/admin capabilities only.)

**Deliberately absent: business-event logging.** Generic policies expect
`order_created`-style domain events in logs; here, ordinary domain CRUD
(bookmark create/edit/delete) is covered by request spans, and every
consequential action is in the audit trail. Logging it again would build a
shadow audit system out of diagnostics — exactly the boundary this document
draws.

**Dependencies — all components:**

| `event` | level | when |
|---|---|---|
| `dependency_call_failed` | `ERROR` (`WARN` if retried successfully) | DB, Redis, Keycloak, backend upstream; include `dependency`, `duration_ms`, `error_code` |
| `retry_exhausted` | `ERROR` | terminal failure after retries |

**Per-request records:** when tracing is enabled, OTEL server spans are the
per-request record and satisfy this requirement. Console access logs are
optional; if emitted: method, route, status, duration, `actor` — and
`/healthz`/`/readyz` probes MUST be excluded (probe noise drowns traffic at
one line per few seconds). Readiness **state transitions** are a different
matter: ready→not-ready MUST log at `WARN`, recovery at `INFO` — the
transition is signal, the individual probe is noise.

## 6. What must never be logged

Never, at any level, not truncated, not "just in dev":

- access / refresh / ID tokens, JWTs, API keys; `Authorization`,
  `Cookie`/`Set-Cookie` headers; raw session identifiers
- passwords and password hashes, MFA codes, private keys
- the OIDC client secret, DB credentials, any connection-string secret
- request/response bodies by default — a specific `DEBUG` diagnostic MAY
  include a length-capped, field-allowlisted excerpt, never the raw body
- data smuggled in by side channels: stack traces MUST NOT embed SQL with
  bound parameter values (ORM/driver parameter logging stays off outside
  local dev), and if query strings or `Referer` values are ever logged they
  count as client-controlled input under the sanitization rule below and
  MUST NOT carry secrets

Prefer: internal ids, resource references, token fingerprints, stable error
codes. Anything client-controlled (URLs, titles, comments, usernames in
free text) MUST be sanitized before logging: strip control characters,
encode newlines, cap length — the Vite client-log forwarder
(`frontends/react/vite.config.ts`) is the reference implementation; server
logs get no weaker rule. Usernames as `actor` are fine: `preferred_username`
*is* the identity here. Log storms MUST be preventable: repeated identical
failures SHOULD be rate-limited or aggregated.

## 7. Correlation

- The gateway MUST propagate W3C `traceparent` to the backend on proxied
  requests when telemetry is enabled — one browser action, one trace across
  gateway and backend.
- No custom correlation-ID or request-ID headers; the repo standardizes on
  OpenTelemetry (in a fully-traced synchronous system a request id adds
  nothing over `trace_id`). This holds because every hop today is
  synchronous HTTP, where trace context auto-propagates. If an
  implementation ever grows an async boundary (message queue, scheduled job,
  webhook), trace context does not survive it automatically — such work MUST
  then carry an explicit correlation id as a join key, and this section gets
  revisited.

## 8. Configuration

| Variable | Default | Purpose |
|---|---|---|
| `LOG_LEVEL` | `info` | minimum console severity (`error`, `warn`, `info`, `debug`), mapped to the stack's native mechanism |
| `LOG_FORMAT` | `json` | `text` opts into human-readable console output for local development; production stays `json` |
| `OTEL_SDK_DISABLED` | `true` | `false` additionally exports logs (with traces/metrics) over OTLP |

Env-only configuration, as everywhere in the repo: no logging config files,
no per-environment profiles.

## 9. Frontends

- Production bundles MUST NOT `console.log` in normal operation; the browser
  console is reserved for actual errors. Users' consoles are not a log sink.
- Dev mode mirrors the browser console to the dev server (`[browser]` lines,
  `.logs/frontend.log`); that forwarding MUST stay dev-only, sanitize its
  input, and never re-log into the app.
- Dev mode SHOULD additionally log user actions through that same channel at
  `DEBUG`, so a click is traceable to what it caused without a debugger:
  clicks and form submits (`[action]`, including dead clicks on nothing
  interactive), route changes (`[nav]`), and same-origin `/api`/`/auth`
  request outcomes with method, path, status, and duration (`[api]`). Action
  lines carry element labels and URLs only — never field values (§6 applies
  in full; reading `.value` would put user input and passwords in the log).
  Like the forwarding itself, this MUST be dev-only and absent from
  production bundles.
- A label alone is ambiguous for controls repeated per row ("Dismiss" exists
  once per report): containers that own an entity — table rows, cards,
  dialogs — SHOULD tag it as `data-ctx="<type>:<id>"` (e.g. `report:123`),
  and the action logger appends the ancestor chain to the line
  (`click button "Dismiss" in report:123 @ /admin/reports`). Ids, not
  payload text — the same preference §6 states for server logs.

## 10. Conformance

| Requirement | spring-kotlin | yarp | spring-cloud-gateway | react |
|---|---|---|---|---|
| stdout-only logging | ✅ | ✅ | ✅ | n/a |
| OTLP log export behind `OTEL_SDK_DISABLED` | ✅ (Java agent) | ✅ (.NET SDK) | ✅ (Java agent) | n/a |
| lifecycle events at `INFO` | ✅ | ✅ | ✅ | n/a |
| expected 4xx not logged as errors | ✅ | ✅ | ✅ | n/a |
| secrets kept out of logs | ✅ | ✅ | ✅ | ✅ |
| `LOG_LEVEL` honored | ✅ | ✅ | ✅ | n/a |
| trace id on console lines when tracing on | ✅ | ✅ | ✅ | n/a |
| stable `event` names (§5: lifecycle, session, security, moderation) | ✅ | ✅ | ✅ | n/a |
| dependency events (§5: `dependency_call_failed`, `retry_exhausted`) | ❌ gap | ❌ gap¹ | ❌ gap¹ | n/a |
| JSON console by default (`LOG_FORMAT`) | ✅ | ✅ | ✅ | n/a |
| dev-only console forwarding, sanitized | n/a | n/a | n/a | ✅ |
| dev-only user-action log (§9: `[action]`/`[nav]`/`[api]`, no field values) | n/a | n/a | n/a | ✅ |

¹ both gateways emit `dependency_call_failed` for Keycloak token-refresh
outages, but Redis and the backend upstream are still uncovered — partial
coverage keeps the row a gap.

Gaps are tracked here on purpose: a new implementation must satisfy every
row, and any `❌` an implementation accrues is its agreed, visible backlog.

Pre-release checklist (per implementation):

```text
[ ] Events carry timestamp, level, message; §5 events carry event + outcome.
[ ] JSON console by default; LOG_FORMAT=text reserved for local dev.
[ ] Trace context present on exported records; console lines link to traces.
[ ] Session, security-signal, moderation and dependency events emitted per §5.
[ ] 5xx at ERROR with stack; expected 4xx at INFO or below.
[ ] Secrets and tokens absent at every level (§6), client input sanitized.
[ ] Health probes excluded from any access logging.
[ ] LOG_LEVEL honored; DEBUG safe per §6; INFO is the default.
[ ] Logging failure cannot crash or block the service.
```

## Appendix A — platform requirements (operator scope)

Out of scope for this repo's dev-grade observability stack; binding for a
real deployment of any Stackverse combination.

- **Centralization:** all container stdout shipped to one searchable
  platform; filtering by service, version, actor, resource; correlation by
  trace id.
- **Protection:** role-based access; encryption in transit and at rest;
  security-relevant logs append-only or tamper-evident; stricter access for
  security logs than for general application logs (OWASP: logs are
  themselves an attack target). Pipeline-level redaction (e.g. the OTel
  Collector redaction processor) as defense in depth — emitters stay
  primarily responsible for §6, the pipeline catches what slips.
- **Retention** (baseline, adjust to legal/compliance context): debug 7–14
  days · application 30–90 days · errors 90–180 days · security 180–400
  days — typically tiered hot (active incidents) / warm (trends, security) /
  cold (compliance evidence). The **audit trail retention is a
  product/database concern** (SPEC rule 16), not a log-pipeline concern.
- **Monitoring:** alerts on pipeline failure, missing logs, and abnormal
  volume; security events feed detection rules; hosts time-synchronized
  (NTP) so cross-service ordering holds.

## References

- OWASP Logging Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
- OWASP Logging Vocabulary Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Logging_Vocabulary_Cheat_Sheet.html
- NIST SP 800-92, Guide to Computer Security Log Management — https://csrc.nist.gov/pubs/sp/800/92/final
- CNCF TAG Observability whitepaper — https://github.com/cncf/tag-observability/blob/main/whitepaper.md
- Coralogix, Application Logging Best Practices — https://coralogix.com/guides/application-performance-monitoring/application-logging-best-practices/
