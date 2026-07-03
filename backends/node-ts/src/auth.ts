import { createRemoteJWKSet, jwtVerify, errors as joseErrors, type JWTPayload } from "jose";
import type { FastifyInstance, FastifyRequest } from "fastify";
import { config } from "./config.js";
import { pool } from "./db.js";
import { logEvent } from "./logging.js";
import { ForbiddenProblem, UnauthorizedProblem, firstParam, sendProblem } from "./problems.js";
import { localize, resolveLanguage } from "./i18n.js";

/** The two application roles; everything else in `realm_access.roles` is Keycloak plumbing. */
export const APP_ROLES = ["moderator", "admin"] as const;
export type AppRole = (typeof APP_ROLES)[number];

export interface Caller {
  username: string;
  roles: string[];
  name?: string;
  email?: string;
}

declare module "fastify" {
  interface FastifyRequest {
    caller: Caller | null;
  }
}

/**
 * Signature keys come straight from `OIDC_JWKS_URI` when set (compose: the
 * issuer host is not dialable from a container), otherwise from the issuer's
 * OIDC discovery document. Resolved lazily and cached, so the service comes
 * up even while the IdP is still booting.
 */
let jwks: ReturnType<typeof createRemoteJWKSet> | undefined;

async function keySet(): Promise<ReturnType<typeof createRemoteJWKSet>> {
  if (jwks) return jwks;
  let jwksUri = config.oidc.jwksUri;
  if (!jwksUri) {
    const discoveryUrl = `${config.oidc.issuerUri}/.well-known/openid-configuration`;
    const startedAt = Date.now();
    try {
      const response = await fetch(discoveryUrl);
      if (!response.ok) throw new Error(`discovery answered ${response.status}`);
      jwksUri = ((await response.json()) as { jwks_uri: string }).jwks_uri;
    } catch (error) {
      logEvent("error", "dependency_call_failed", "failure", "OIDC discovery failed", {
        dependency: "keycloak",
        duration_ms: Date.now() - startedAt,
        error_code: "oidc_discovery_failed",
      });
      throw error;
    }
  }
  jwks = createRemoteJWKSet(new URL(jwksUri));
  return jwks;
}

interface RealmAccessPayload extends JWTPayload {
  preferred_username?: string;
  name?: string;
  email?: string;
  realm_access?: { roles?: string[] };
}

async function verifyBearer(token: string): Promise<Caller> {
  const { payload } = await jwtVerify<RealmAccessPayload>(token, await keySet(), {
    issuer: config.oidc.issuerUri,
    audience: config.oidc.audience,
  });
  const username = payload.preferred_username;
  if (!username) throw new joseErrors.JWTClaimValidationFailed("missing preferred_username", payload);
  const caller: Caller = {
    username,
    roles: (payload.realm_access?.roles ?? []).filter((role): role is string => typeof role === "string"),
  };
  if (payload.name) caller.name = payload.name;
  if (payload.email) caller.email = payload.email;
  return caller;
}

interface AccountState {
  status: "active" | "blocked";
}

/** SPEC rule 16: upsert on every authenticated request; returns the current account state. */
async function recordSeen(username: string): Promise<AccountState> {
  const result = await pool.query(
    `insert into user_accounts (username, first_seen, last_seen, status)
     values ($1, $2, $2, 'active')
     on conflict (username) do update set last_seen = excluded.last_seen
     returning status`,
    [username, new Date()],
  );
  return result.rows[0] as AccountState;
}

/**
 * Bearer authentication for every request: a missing token leaves the request
 * anonymous (whether that is acceptable is each endpoint's decision), a
 * presented-but-invalid token is always a 401, and a valid token from a
 * blocked account is a 403 with a localized problem document (SPEC rule 17) —
 * the lazy account upsert (rule 16) happens on the same hook.
 */
export function registerAuth(app: FastifyInstance): void {
  app.decorateRequest("caller", null);
  app.addHook("onRequest", async (request, reply) => {
    request.caller = null;
    const header = request.headers.authorization;
    if (!header?.startsWith("Bearer ")) return;
    let caller: Caller;
    try {
      caller = await verifyBearer(header.slice("Bearer ".length));
    } catch (error) {
      // an expected 401 and a security signal, never above INFO (docs/LOGGING.md §3)
      logEvent("info", "jwt_validation_failed", "failure", "Rejected a bearer token", {
        error_code: (error as { code?: string }).code?.toLowerCase() ?? "invalid_token",
      });
      return sendProblem(reply, 401, "Unauthorized", "Missing or invalid bearer token.");
    }
    const account = await recordSeen(caller.username);
    if (account.status === "blocked") {
      logEvent("warn", "blocked_user_rejected", "denied", "Refused a request from a blocked account", {
        actor: caller.username,
      });
      const language = await resolveLanguage(
        firstParam((request.query as Record<string, unknown>)["lang"]),
        request.headers["accept-language"],
      );
      return sendProblem(reply, 403, "Forbidden", await localize("error.account.blocked", language));
    }
    request.caller = caller;
  });
}

/** Rule 2: every endpoint requires authentication unless the spec says otherwise. */
export function requireCaller(request: FastifyRequest): Caller {
  if (!request.caller) throw new UnauthorizedProblem();
  return request.caller;
}

/**
 * Endpoints check for the single role they need; the admin ⊃ moderator
 * hierarchy lives in Keycloak as a composite role — never re-implemented here.
 */
export function requireRole(request: FastifyRequest, role: AppRole): Caller {
  const caller = requireCaller(request);
  if (!caller.roles.includes(role)) {
    logEvent("info", "authz_denied", "denied", "Denied a request lacking the required role", {
      actor: caller.username,
    });
    throw new ForbiddenProblem("You do not have the role required for this operation.");
  }
  return caller;
}

/** `GET /api/v1/me` — identity as derived from the validated JWT (SPEC rule 6). */
export function toMeResponse(caller: Caller): Record<string, unknown> {
  const roles = caller.roles.filter((role) => (APP_ROLES as readonly string[]).includes(role)).sort();
  return {
    username: caller.username,
    ...(caller.name !== undefined && { name: caller.name }),
    ...(caller.email !== undefined && { email: caller.email }),
    roles,
  };
}
