import { Catch, HttpException, Injectable, type ArgumentsHost, type ExceptionFilter } from "@nestjs/common";
import type { FastifyReply, FastifyRequest } from "fastify";
import pg from "pg";
import { localize, resolveRequestLanguage } from "./i18n.js";
import { logEvent } from "./logging.js";
import { ApiProblem, ValidationProblem, sendProblem } from "./problems.js";

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
  const language = await resolveRequestLanguage(
    request.query as Record<string, unknown>,
    request.headers["accept-language"],
  );
  const errors = await Promise.all(
    problem.violations.map(async (violation) => ({
      field: violation.field,
      messageKey: violation.messageKey,
      message: await localize(violation.messageKey, language),
    })),
  );
  return sendProblem(reply, 400, "Bad Request", "Request validation failed.", errors);
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

function httpExceptionDetail(error: HttpException): string | undefined {
  const response = error.getResponse();
  if (typeof response === "string") return response;
  if (typeof response === "object" && response !== null) {
    const message = (response as { message?: unknown }).message;
    if (typeof message === "string") return message;
    if (Array.isArray(message)) return message.map(String).join("; ");
  }
  return error.message;
}

@Catch()
@Injectable()
export class ProblemFilter implements ExceptionFilter {
  async catch(error: unknown, host: ArgumentsHost): Promise<FastifyReply> {
    const http = host.switchToHttp();
    return sendProblemForError(error, http.getRequest<FastifyRequest>(), http.getResponse<FastifyReply>());
  }
}

export async function sendProblemForError(
  error: unknown,
  request: FastifyRequest,
  reply: FastifyReply,
): Promise<FastifyReply> {
  if (error instanceof ValidationProblem) {
    return sendValidationProblem(request, reply, error);
  }
  if (error instanceof ApiProblem) {
    const detail = error.detailKey
      ? await localize(
          error.detailKey,
          await resolveRequestLanguage(request.query as Record<string, unknown>, request.headers["accept-language"]),
        )
      : error.detail;
    return sendProblem(reply, error.status, error.title, detail);
  }
  if (error instanceof HttpException) {
    const status = error.getStatus();
    const title = status === 404 ? "Not Found" : error.name.replace(/Exception$/, "");
    return sendProblem(reply, status, title, httpExceptionDetail(error));
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
}
