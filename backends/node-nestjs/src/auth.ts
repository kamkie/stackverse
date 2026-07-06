import { Injectable, type CanActivate, type ExecutionContext } from "@nestjs/common";
import { createRemoteJWKSet, decodeProtectedHeader, jwtVerify, errors as joseErrors, type JWTPayload } from "jose";
import type { FastifyInstance, FastifyRequest } from "fastify";
import { config } from "./config.js";
import { pool } from "./db.js";
import { logEvent } from "./logging.js";
import { ForbiddenProblem, UnauthorizedProblem } from "./problems.js";
import { localize, resolveRequestLanguage } from "./i18n.js";

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

const authenticatedRequests = new WeakSet<FastifyRequest>();

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
  // Parse obviously malformed compact tokens before OIDC discovery/JWKS lookup,
  // so "Bearer not-a-jwt" remains a local 401 even when Keycloak is unavailable.
  decodeProtectedHeader(token);
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
 * the lazy account upsert (rule 16) happens here.
 */
export async function authenticateBearerRequest(request: FastifyRequest): Promise<void> {
  if (authenticatedRequests.has(request)) return;
  authenticatedRequests.add(request);
  request.caller = null;
  // Fastify runs global hooks for unmatched requests too; keep those as 404s
  // instead of letting bearer validation rewrite them to 401/403 or touch DB.
  if (request.routeOptions.url === undefined) return;
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
    throw new UnauthorizedProblem("Missing or invalid bearer token.");
  }
  const account = await recordSeen(caller.username);
  if (account.status === "blocked") {
    logEvent("warn", "blocked_user_rejected", "denied", "Refused a request from a blocked account", {
      actor: caller.username,
    });
    const language = await resolveRequestLanguage(
      request.query as Record<string, unknown>,
      request.headers["accept-language"],
    );
    throw new ForbiddenProblem(await localize("error.account.blocked", language));
  }
  request.caller = caller;
}

/**
 * Fastify parses JSON before Nest guards run. Keep auth on `onRequest` so an
 * invalid or blocked bearer token is rejected consistently before parser
 * failures, while the Nest guard remains an idempotent safety net.
 */
export function registerFastifyAuth(app: FastifyInstance): void {
  app.decorateRequest("caller", null);
  // Rate limiting belongs at the Stackverse gateway/operator boundary; see app.ts.
  app.addHook("onRequest", authenticateBearerRequest); // codeql[js/missing-rate-limiting]
}

@Injectable()
export class BearerAuthGuard implements CanActivate {
  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<FastifyRequest>();
    await authenticateBearerRequest(request);
    return true;
  }
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
