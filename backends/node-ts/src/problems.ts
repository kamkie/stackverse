import type { FastifyReply } from "fastify";

/** Renders an RFC 9457 problem document (backends/README.md: errors are problem documents). */
export function sendProblem(
  reply: FastifyReply,
  status: number,
  title: string,
  detail?: string,
  errors?: { field: string; messageKey: string; message: string }[],
): FastifyReply {
  return reply
    .code(status)
    .type("application/problem+json")
    .send({
      type: "about:blank",
      title,
      status,
      ...(detail !== undefined && { detail }),
      ...(errors !== undefined && { errors }),
    });
}

/**
 * Application errors that map 1:1 onto RFC 9457 problem documents.
 * Thrown from route handlers and services, translated to responses by the
 * error handler in `app.ts`.
 */
export class ApiProblem extends Error {
  constructor(
    readonly status: number,
    readonly title: string,
    readonly detail?: string,
    /** Optional key into the messages table; resolved to a localized `detail`. */
    readonly detailKey?: string,
  ) {
    super(detail ?? title);
  }
}

/** Resource missing — or deliberately masked (SPEC rule 1: existence is not disclosed). */
export class NotFoundProblem extends ApiProblem {
  constructor() {
    super(404, "Not Found");
  }
}

/** Anonymous caller on an endpoint that needs authentication (e.g. non-public listing). */
export class UnauthorizedProblem extends ApiProblem {
  constructor(detail = "Authentication is required.") {
    super(401, "Unauthorized", detail);
  }
}

export class ForbiddenProblem extends ApiProblem {
  constructor(detail: string, detailKey?: string) {
    super(403, "Forbidden", detail, detailKey);
  }
}

export class ConflictProblem extends ApiProblem {
  constructor(detail: string, detailKey?: string) {
    super(409, "Conflict", detail, detailKey);
  }
}

export class BadRequestProblem extends ApiProblem {
  constructor(detail: string) {
    super(400, "Bad Request", detail);
  }
}

/** One field-level validation failure; `message` gets localized when the problem is rendered. */
export interface FieldViolation {
  field: string;
  messageKey: string;
}

/** Validation failure carrying field-level errors (SPEC rules 5 + 11). */
export class ValidationProblem extends Error {
  constructor(readonly violations: FieldViolation[]) {
    super("Validation failed");
  }
}

/** Collects violations and throws once at the end, so all field errors are reported together. */
export class Validator {
  private readonly violations: FieldViolation[] = [];

  reject(field: string, messageKey: string): void {
    this.violations.push({ field, messageKey });
  }

  check(condition: boolean, field: string, messageKey: string): void {
    if (!condition) this.reject(field, messageKey);
  }

  throwIfInvalid(): void {
    if (this.violations.length > 0) throw new ValidationProblem(this.violations);
  }
}

/** Query parameters arrive as string | string[] — a repeated parameter is only valid where documented. */
export const singleParam = (value: unknown, name: string): string | undefined => {
  if (value === undefined) return undefined;
  if (typeof value === "string") return value;
  throw new BadRequestProblem(`${name} must not be repeated`);
};

/** Tolerant single value: a repeated parameter takes the first occurrence instead of erroring.
 *  Used for `lang`, where SPEC rule 8 says resolution falls back and never errors. */
export const firstParam = (value: unknown): string | undefined => {
  if (typeof value === "string") return value;
  if (Array.isArray(value) && typeof value[0] === "string") return value[0];
  return undefined;
};

export const multiParam = (value: unknown): string[] => {
  if (value === undefined) return [];
  if (typeof value === "string") return [value];
  return (value as string[]).map(String);
};

/** Shared bounds for `page`/`size` query parameters (spec: size 1–100, default 20). */
export function requireValidPaging(query: Record<string, unknown>): { page: number; size: number } {
  const page = intParam(singleParam(query["page"], "page"), 0, "page");
  const size = intParam(singleParam(query["size"], "size"), 20, "size");
  if (page < 0) throw new BadRequestProblem("page must not be negative");
  if (size < 1 || size > 100) throw new BadRequestProblem("size must be between 1 and 100");
  return { page, size };
}

function intParam(value: string | undefined, fallback: number, name: string): number {
  if (value === undefined || value === "") return fallback;
  const parsed = Number(value);
  if (!Number.isInteger(parsed)) throw new BadRequestProblem(`${name} must be an integer`);
  return parsed;
}

export function requireMaxLength(value: string | undefined, max: number, name: string): void {
  if (value !== undefined && value.length > max) {
    throw new BadRequestProblem(`${name} must be at most ${max} characters`);
  }
}

/** LIKE/ILIKE patterns treat client input as literal text: escape the wildcards. */
export const escapeLike = (value: string): string =>
  value.replaceAll("\\", "\\\\").replaceAll("%", "\\%").replaceAll("_", "\\_");

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A malformed id cannot name an existing resource — same 404 as an unknown one. */
export function parseUuid(value: string): string {
  if (!UUID_PATTERN.test(value)) throw new NotFoundProblem();
  return value.toLowerCase();
}

/** Drops null/undefined properties so optional fields are absent on the wire, not `null`. */
export function omitNulls<T extends Record<string, unknown>>(object: T): Partial<T> {
  return Object.fromEntries(
    Object.entries(object).filter(([, value]) => value !== null && value !== undefined),
  ) as Partial<T>;
}
