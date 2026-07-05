import cookie from "@fastify/cookie";
import rateLimit from "@fastify/rate-limit";
import replyFrom from "@fastify/reply-from";
import fastifyStatic from "@fastify/static";
import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";
import { loadConfig, type GatewayConfig } from "./config.js";
import { logEvent, logger, sanitizeLogValue } from "./logging.js";
import { OidcClient, IdpUnavailableError, usernameFromIdToken, type FetchLike, type TokenSet } from "./oidc.js";
import { sendProblem } from "./problems.js";
import { proxyRequest } from "./proxy.js";
import {
  SESSION_COOKIE,
  XSRF_HEADER,
  applySecurityHeaders,
  canonicalPublicOrigin,
  hasValidCsrf,
  isSameOriginStateChange,
  issueCsrfCookie,
  randomToken,
} from "./security.js";
import { RedisSessionStore, type GatewaySession, type SessionStore } from "./session-store.js";

const SESSION_TTL_SECONDS = 8 * 60 * 60;
const LOGIN_STATE_TTL_SECONDS = 10 * 60;
const REFRESH_SKEW_MS = 30_000;
const AUTH_RATE_LIMIT = { max: 60, timeWindow: "1 minute" } as const;
const SPA_RATE_LIMIT = { max: 600, timeWindow: "1 minute" } as const;

export interface BuildAppOptions {
  config?: GatewayConfig;
  sessionStore?: SessionStore;
  oidcClient?: OidcClient;
  fetchImpl?: FetchLike;
}

export async function buildApp(options: BuildAppOptions = {}): Promise<FastifyInstance> {
  const gateway = options.config ?? loadConfig();
  const fetchImpl = options.fetchImpl ?? fetch;
  const sessionStore = options.sessionStore ?? new RedisSessionStore(gateway.redisUrl);
  const oidc = options.oidcClient ?? new OidcClient(gateway, fetchImpl);
  const expectedOrigin = canonicalPublicOrigin(gateway.publicUrl);
  const ownsSessionStore = options.sessionStore === undefined;

  const app = Fastify({ logger: false });
  await app.register(cookie);
  await app.register(rateLimit, {
    global: false,
    max: 600,
    timeWindow: "1 minute",
  });
  await app.register(replyFrom, { disableRequestLogging: true });
  app.removeAllContentTypeParsers();
  app.addContentTypeParser("*", (_request, payload, done) => {
    done(null, payload);
  });

  app.addHook("onRequest", async (request, reply) => {
    issueCsrfCookie(request, reply, gateway.cookiesSecure);
  });

  app.addHook("onSend", async (request, reply, payload) => {
    applySecurityHeaders(request, reply, gateway.cookiesSecure);
    return payload;
  });

  app.setErrorHandler((error, _request, reply) => {
    if (isRateLimitError(error)) {
      return reply.code(429).send({
        statusCode: 429,
        error: "Too Many Requests",
        message: error.message,
      });
    }
    logger.error({ err: error }, "Unhandled gateway error");
    sendProblem(reply, 500, "Internal Server Error", "The gateway failed to handle the request.");
  });

  app.addHook("onClose", async () => {
    if (ownsSessionStore) {
      await sessionStore.close?.();
    }
  });

  app.get("/auth/login", { config: { rateLimit: AUTH_RATE_LIMIT } }, async (_request, reply) => {
    const state = randomToken(24);
    const codeVerifier = randomToken(32);
    const nonce = randomToken(24);
    await sessionStore.setLoginState(state, { codeVerifier, nonce, createdAt: Date.now() }, LOGIN_STATE_TTL_SECONDS);
    try {
      return reply.redirect(await oidc.authorizationUrl(state, codeVerifier, nonce), 302);
    } catch (error) {
      logEvent("error", "dependency_call_failed", "failure", "OIDC discovery failed during login", {
        dependency: "keycloak",
        error_code: error instanceof Error ? error.name : "oidc_discovery_failed",
      });
      return sendProblem(reply, 503, "Service Unavailable", "Authentication is temporarily unavailable; please retry.");
    }
  });

  app.get("/auth/callback", { config: { rateLimit: AUTH_RATE_LIMIT } }, async (request, reply) => {
    const query = request.query as Record<string, string | undefined>;
    if (query["error"] || !query["code"] || !query["state"]) {
      logEvent("info", "oidc_callback_completed", "failure", "Authorization code flow failed", {
        error_code: sanitizeLogValue(query["error"] ?? "invalid_callback"),
      });
      return reply.redirect("/", 302);
    }

    const loginState = await sessionStore.consumeLoginState(query["state"]);
    if (!loginState) {
      logEvent("info", "oidc_callback_completed", "failure", "Authorization code flow failed", {
        error_code: "invalid_state",
      });
      return reply.redirect("/", 302);
    }

    try {
      const tokens = await oidc.exchangeCode(query["code"], loginState.codeVerifier);
      if (!tokens.idToken) throw new Error("missing_id_token");
      const payload = await oidc.verifyIdToken(tokens.idToken, loginState.nonce);
      const username = usernameFromIdToken(payload);
      const session = sessionFromTokens(username, tokens);
      const sessionId = await sessionStore.createSession(session, SESSION_TTL_SECONDS);
      setSessionCookie(reply, sessionId, gateway.cookiesSecure);
      logEvent("info", "oidc_callback_completed", "success", "Authorization code flow completed", { actor: username });
      logEvent("info", "session_created", "success", "Session stored in Redis, cookie issued", { actor: username });
      return reply.redirect("/", 302);
    } catch (error) {
      logEvent("info", "oidc_callback_completed", "failure", "Authorization code flow failed", {
        error_code: error instanceof Error ? sanitizeLogValue(error.message) : "callback_failed",
      });
      return reply.redirect("/", 302);
    }
  });

  app.get("/auth/session", async (request, reply) => {
    const loaded = await loadSession(request, sessionStore);
    if (!loaded) {
      return reply.send({ authenticated: false });
    }
    return reply.send({ authenticated: true, username: loaded.session.username });
  });

  app.post("/auth/logout", async (request, reply) => {
    const loaded = await loadSession(request, sessionStore);
    if (loaded) {
      await sessionStore.destroySession(loaded.id);
      clearSessionCookie(reply, gateway.cookiesSecure);
      logEvent("info", "session_destroyed", "success", "Session destroyed by user logout", {
        reason: "logout",
        actor: loaded.session.username,
      });
      if (loaded.session.refreshToken) {
        await oidc.logout(loaded.session.refreshToken);
      }
    } else {
      clearSessionCookie(reply, gateway.cookiesSecure);
    }
    return reply.code(204).send();
  });

  app.all("/api/*", async (request, reply) => {
    if (!isSameOriginStateChange(request, expectedOrigin)) {
      logEvent("info", "csrf_validation_failed", "denied", "Rejected a cross-origin state-changing /api request", {
        method: sanitizeLogValue(request.method),
        path: sanitizeLogValue(request.url.split("?")[0]),
      });
      return sendProblem(reply, 403, "Forbidden", "Cross-origin state-changing requests are not supported.");
    }
    if (!hasValidCsrf(request)) {
      logEvent("info", "csrf_validation_failed", "denied", "Rejected a state-changing /api request without a matching CSRF header", {
        method: sanitizeLogValue(request.method),
        path: sanitizeLogValue(request.url.split("?")[0]),
      });
      return sendProblem(reply, 403, "Forbidden", `Missing or mismatched ${XSRF_HEADER.toUpperCase()} header.`);
    }

    const loaded = await loadSession(request, sessionStore);
    let accessToken: string | undefined;
    if (loaded) {
      try {
        accessToken = await accessTokenForSession(loaded.id, loaded.session, sessionStore, oidc);
      } catch (error) {
        if (error instanceof IdpUnavailableError) {
          return sendProblem(reply, 503, "Service Unavailable", "Authentication is temporarily unavailable; please retry.");
        }
        throw error;
      }
      if (!accessToken) {
        await sessionStore.destroySession(loaded.id);
        clearSessionCookie(reply, gateway.cookiesSecure);
        logEvent("info", "session_destroyed", "success", "Session destroyed after a failed token refresh; request degraded to anonymous", {
          reason: "token_refresh_failed",
          actor: loaded.session.username,
        });
      }
    }

    return proxyRequest(request, reply, gateway.backendUrl, "backend", accessToken);
  });

  const frontendUrl = gateway.frontendUrl;
  if (frontendUrl) {
    app.all("/*", { config: { rateLimit: SPA_RATE_LIMIT } }, async (request, reply) => {
      return proxyRequest(request, reply, frontendUrl, "frontend");
    });
  } else {
    await registerStaticSpa(app, gateway.spaRoot);
  }

  return app;
}

function isRateLimitError(error: unknown): error is Error & { statusCode: 429 } {
  return error instanceof Error && "statusCode" in error && error.statusCode === 429;
}

async function registerStaticSpa(app: FastifyInstance, root: string): Promise<void> {
  await app.register(async (spa) => {
    spa.addHook("preHandler", spa.rateLimit(SPA_RATE_LIMIT));
    await spa.register(fastifyStatic, {
      root,
      prefix: "/",
      redirect: false,
      wildcard: true,
    });
    spa.setNotFoundHandler(async (request, reply) => {
      if (request.method !== "GET" && request.method !== "HEAD") {
        return sendProblem(reply, 404, "Not Found", "No route matched the request.");
      }
      return reply.sendFile("index.html", root);
    });
  });
}

function sessionFromTokens(username: string, tokens: TokenSet): GatewaySession {
  const now = Date.now();
  const session: GatewaySession = {
    username,
    accessToken: tokens.accessToken,
    expiresAt: now + tokens.expiresIn * 1000,
    createdAt: now,
    updatedAt: now,
  };
  if (tokens.refreshToken) session.refreshToken = tokens.refreshToken;
  if (tokens.idToken) session.idToken = tokens.idToken;
  return session;
}

async function accessTokenForSession(
  id: string,
  session: GatewaySession,
  sessionStore: SessionStore,
  oidc: OidcClient,
): Promise<string | undefined> {
  if (session.accessToken && session.expiresAt - REFRESH_SKEW_MS > Date.now()) {
    return session.accessToken;
  }
  if (!session.refreshToken) {
    return undefined;
  }
  const refreshed = await oidc.refresh(session.refreshToken);
  if (!refreshed) {
    return undefined;
  }

  const updated: GatewaySession = {
    ...session,
    accessToken: refreshed.accessToken,
    expiresAt: Date.now() + refreshed.expiresIn * 1000,
    updatedAt: Date.now(),
  };
  if (refreshed.refreshToken) updated.refreshToken = refreshed.refreshToken;
  if (refreshed.idToken) updated.idToken = refreshed.idToken;
  await sessionStore.saveSession(id, updated, SESSION_TTL_SECONDS);
  return updated.accessToken;
}

async function loadSession(
  request: FastifyRequest,
  sessionStore: SessionStore,
): Promise<{ id: string; session: GatewaySession } | null> {
  const id = request.cookies[SESSION_COOKIE];
  if (!id) return null;
  const session = await sessionStore.getSession(id);
  return session ? { id, session } : null;
}

function setSessionCookie(reply: FastifyReply, id: string, secure: boolean): void {
  reply.setCookie(SESSION_COOKIE, id, {
    httpOnly: true,
    sameSite: "lax",
    secure,
    path: "/",
    maxAge: SESSION_TTL_SECONDS,
  });
}

function clearSessionCookie(reply: FastifyReply, secure: boolean): void {
  reply.clearCookie(SESSION_COOKIE, {
    httpOnly: true,
    sameSite: "lax",
    secure,
    path: "/",
  });
}
