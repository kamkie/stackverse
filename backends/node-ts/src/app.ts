import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";
import pg from "pg";
import { logger, logEvent } from "./logging.js";
import { registerAuth } from "./auth.js";
import { registerBookmarkRoutes } from "./routes/bookmarks.js";
import { registerMessageRoutes } from "./routes/messages.js";
import { registerModerationRoutes } from "./routes/moderation.js";
import { registerAdminUserRoutes } from "./routes/admin-users.js";
import { registerAuditRoutes } from "./routes/audit-log.js";
import { registerStatsRoutes } from "./routes/stats.js";
import { registerMetaRoutes } from "./routes/meta.js";
import { localize, resolveLanguage } from "./i18n.js";
import { ApiProblem, ValidationProblem, firstParam, sendProblem } from "./problems.js";

/** SPEC rules 5 + 11: field errors with a `validation.*` key and a localized message. */
async function sendValidationProblem(
  request: FastifyRequest,
  reply: FastifyReply,
  problem: ValidationProblem,
): Promise<FastifyReply> {
  // expected client behavior — a security signal, never above INFO (docs/LOGGING.md §3);
  // field names and message keys are server-defined, so nothing client-controlled is logged
  logEvent("info", "input_validation_failed", "failure", "Request validation failed", {
    error_code: "validation_failed",
    fields: problem.violations.map((violation) => violation.field).join(","),
  });
  const language = await requestLanguage(request);
  const errors = await Promise.all(
    problem.violations.map(async (violation) => ({
      field: violation.field,
      messageKey: violation.messageKey,
      message: await localize(violation.messageKey, language),
    })),
  );
  return sendProblem(reply, 400, "Bad Request", "Request validation failed.", errors);
}

async function requestLanguage(request: FastifyRequest): Promise<string> {
  return resolveLanguage(
    firstParam((request.query as Record<string, unknown>)["lang"]),
    request.headers["accept-language"],
  );
}

/** Node socket-level failures that mean "the database was unreachable", not a query bug. */
const CONNECTION_ERROR_CODES = new Set(["ECONNREFUSED", "ECONNRESET", "ETIMEDOUT", "EPIPE", "ENOTFOUND", "EAI_AGAIN"]);

/** Returns a stable error code when `error` is a PostgreSQL failure, else undefined. */
function dbErrorCode(error: unknown): string | undefined {
  if (error instanceof pg.DatabaseError) return error.code ?? "db_error";
  const code = (error as { code?: string }).code;
  // SQLSTATE class 08 is "connection exception"; the socket codes cover a pool that never connected
  if (typeof code === "string" && (CONNECTION_ERROR_CODES.has(code) || code.startsWith("08"))) return code;
  return undefined;
}

export function buildApp(): FastifyInstance {
  // the cast erases the pino-specific logger generic — routes only need the base instance
  const app = Fastify({
    loggerInstance: logger,
    // OTEL server spans are the per-request record; access logs would only add
    // probe noise (docs/LOGGING.md §5)
    disableRequestLogging: true,
  }) as unknown as FastifyInstance;

  registerAuth(app);
  registerBookmarkRoutes(app);
  registerMessageRoutes(app);
  registerModerationRoutes(app);
  registerAdminUserRoutes(app);
  registerAuditRoutes(app);
  registerStatsRoutes(app);
  registerMetaRoutes(app);

  app.setNotFoundHandler((_request, reply) => {
    sendProblem(reply, 404, "Not Found");
  });

  app.setErrorHandler(async (error, request, reply) => {
    if (error instanceof ValidationProblem) {
      return sendValidationProblem(request, reply, error);
    }
    if (error instanceof ApiProblem) {
      const detail = error.detailKey
        ? await localize(error.detailKey, await requestLanguage(request))
        : error.detail;
      return sendProblem(reply, error.status, error.title, detail);
    }
    // Fastify infrastructure errors (malformed JSON, oversized body, ...) are
    // expected client behavior, not stack-trace material
    const statusCode = (error as { statusCode?: number }).statusCode;
    if (statusCode !== undefined && statusCode >= 400 && statusCode < 500) {
      return sendProblem(reply, statusCode, "Bad Request", (error as Error).message);
    }
    // 5xx: unexpected — always with the stack trace and trace id (docs/LOGGING.md §3).
    // A database failure that reached here is an uncaught dependency error (an
    // expected 23505 is translated to 409 before this handler), so it is the
    // `dependency_call_failed` event with dependency metadata (docs/LOGGING.md §5).
    const dbCode = dbErrorCode(error);
    if (dbCode !== undefined) {
      logEvent("error", "dependency_call_failed", "failure", "PostgreSQL call failed during a request", {
        dependency: "postgres",
        duration_ms: Math.round(reply.elapsedTime),
        error_code: dbCode,
        err: error,
      });
    } else {
      request.log.error({ err: error }, "Unhandled error");
    }
    return sendProblem(reply, 500, "Internal Server Error", "An unexpected error occurred.");
  });

  return app;
}
