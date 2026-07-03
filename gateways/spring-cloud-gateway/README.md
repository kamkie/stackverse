# Gateway: Spring Cloud Gateway (Kotlin)

The Stackverse BFF on Spring Boot 4 / Spring Cloud Gateway (WebFlux) in Kotlin, on
Java 25. Route contract, cookie rules, and the login sequence live in
[docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md); shared env vars in
[gateways/README.md](../README.md).

## How it maps to the contract

| Contract | Here |
|---|---|
| `GET /auth/login` | Spring Security's authorization-request redirect filter, re-anchored from `/oauth2/authorization/{id}` to `/auth/login` via a custom resolver (code flow + PKCE S256) |
| `GET /auth/callback` | `oauth2Login`'s authentication filter with a custom `authenticationMatcher` |
| `POST /auth/logout` | controller: server-to-server RP-initiated logout at Keycloak, session invalidated, `204` |
| `GET /auth/session` | controller reading the session principal (`preferred_username`) |
| `/api/**` | gateway route to `BACKEND_URL` with a hand-rolled token-relay filter |
| `/**` | gateway route to `FRONTEND_URL` when set; otherwise static resources + an `index.html` fallback filter |

## Design notes

- **Stateless process.** The `stackverse_session` cookie carries only the WebSession
  id; the session — security context and OAuth2 tokens included — lives in Redis via
  Spring Session ([SecurityConfig.kt](src/main/kotlin/dev/stackverse/gateway/config/SecurityConfig.kt)
  pins the tokens into the session with `WebSessionServerOAuth2AuthorizedClientRepository`;
  the default would cache them in-process). In-flight OIDC state (authorization
  request, PKCE verifier, nonce) is session data too, so two gateways behind a dumb
  load balancer need no affinity.
- **Token refresh** is a hand-rolled ~50-line call to the token endpoint
  ([AccessTokenManager.kt](src/main/kotlin/dev/stackverse/gateway/relay/AccessTokenManager.kt))
  instead of Spring's `ReactiveOAuth2AuthorizedClientManager`: the exchange is one
  form POST, this repo optimizes for self-contained, readable code, and the
  contract's two refresh-failure modes need exact, distinct handling. Concurrent
  requests may refresh twice; Keycloak allows refresh-token reuse by default, so
  both results are valid.
- **Refresh failures are two different animals** (semantics pinned in
  [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md)). An IdP that *rejects* the
  refresh — an authoritative `400`/`401` from the token endpoint (RFC 6749 §5.2) —
  proves the session is dead: destroy it, `token_refresh_failed` at WARN, degrade
  the request to anonymous. An IdP that is *unavailable* — unreachable, answering
  `5xx`/`429`, or answering garbage — proves nothing about the session, so the
  manager logs `dependency_call_failed` at ERROR (`dependency=keycloak`) and errors
  with `IdpUnavailableException`; the relay filter translates it into a `503`
  problem document and the session — whose refresh token may still be valid —
  survives.
- **Logout without a redirect.** The contract wants `204` from `POST /auth/logout`,
  so RP-initiated logout happens server-to-server: a confidential-client POST of the
  refresh token to Keycloak's end-session endpoint tears down the SSO session
  ([RpInitiatedLogout.kt](src/main/kotlin/dev/stackverse/gateway/auth/RpInitiatedLogout.kt)).
- **Logout semantics: local-first, IdP best-effort.** The local session (Redis entry
  + cookie — the only credential the browser holds) is destroyed *first* because it
  is the only death the gateway can guarantee; the IdP revocation follows on its own
  subscription, detached from the request, with failures logged and swallowed. The
  full argument for this ordering lives in the yarp README and applies here
  verbatim.
- **Anonymous `/api/**` requests relay without a token.** The spec's public surface
  (public bookmark feeds, message reads) works logged-out; the backend owns
  per-endpoint auth. A session that can no longer refresh is destroyed and the
  request degrades to anonymous. CSRF violations → `403` problem document
  (mechanism pinned in [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md)), and the
  browser's cookies, the CSRF header, and any client-supplied `Authorization`
  header are stripped before proxying.
- **Callback failures redirect, never 500.** `error=access_denied` (the user pressed
  Cancel on the Keycloak form) and stale or replayed state both land in the
  authentication failure handler: log `oidc_callback_completed` outcome=failure at
  INFO — the failure *type* only, since the message can echo client-controlled query
  text — then redirect to `/` logged out.
- **No response-header meddling.** Spring Security's default header writers are
  disabled: the gateway adds nothing to the API semantics, and the default
  `Cache-Control: no-store` stamp would break the backend's ETag caching exhibit on
  proxied responses.
- **Logging** (docs/LOGGING.md) uses Spring Boot's built-in structured console
  logging (ECS flavor, UTC timestamps; the OTel agent's MDC puts trace/span ids on
  every line when telemetry is on) by default, the human-readable pattern for
  `LOG_FORMAT=text`. The contract's `event`/`outcome` fields travel as SLF4J
  key-value pairs via the small `logEvent` extension — the same convention as
  `backends/spring-kotlin`.
- **PKCE is forced.** Spring auto-enables PKCE only for public clients; the
  authorization-request customizer adds it for this confidential client too, so all
  gateway stacks exhibit identical wire behavior (the realm requires S256).

## Configuration

All shared variables from [gateways/README.md](../README.md). `SPA_ROOT` defaults
to the bundled `classpath:/static` placeholder page when unset. The OIDC issuer
metadata is resolved at startup, so Keycloak must be reachable when the gateway
boots (compose orders this with a healthcheck; `scripts/dev-stack.*` waits too).

This gateway honors `OIDC_INTERNAL_ISSUER_URI`: the JVM's HTTP clients dial only
the first resolved address, so compose's `localhost → host-gateway` alias trick
(which works for .NET's try-every-address client) cannot reach Keycloak from
inside the network. Discovery is fetched from — and the token, JWKS, and
end-session endpoints are re-based onto — the internal base, while issuer
validation and the browser-facing authorization redirect keep `OIDC_ISSUER_URI`
(the same escape hatch as the backend's `OIDC_JWKS_URI`).

## Run

```sh
# infra first, from the repo root: docker compose up -d
./gradlew bootRun
# → http://localhost:8000, log in as demo/demo
```

## Test

Integration tests boot real Keycloak (realm imported from `infra/keycloak`) and
Redis via Testcontainers, start the gateway on a random port, and drive the real
authorization code flow plus token relay against a stub backend. Docker required.

```sh
./gradlew test
```

## Docker

```sh
docker build -t stackverse/gateway-spring-cloud-gateway:local .
# then from the repo root:
# GATEWAY_IMAGE=stackverse/gateway-spring-cloud-gateway:local docker compose --profile app up
```
