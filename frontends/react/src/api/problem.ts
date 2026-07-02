import type { components } from "./schema";

export type Problem = components["schemas"]["Problem"];
export type FieldError = NonNullable<Problem["errors"]>[number];

/** A non-2xx API response, carrying the RFC 9457 problem document if present. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem | undefined;

  constructor(status: number, problem?: Problem) {
    super(problem?.detail ?? problem?.title ?? `HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }

  get fieldErrors(): FieldError[] {
    return this.problem?.errors ?? [];
  }

  fieldError(field: string): FieldError | undefined {
    return this.fieldErrors.find((e) => e.field === field);
  }
}

/** Field errors of an `ApiError`, or none for any other error. */
export function fieldErrorsOf(error: unknown): FieldError[] {
  return error instanceof ApiError ? error.fieldErrors : [];
}

/**
 * The error for a form field, matching indexed/nested paths too
 * (`tags`, `tags[0]`, `tags.0`).
 */
export function fieldErrorFor(error: unknown, field: string): FieldError | undefined {
  return fieldErrorsOf(error).find(
    (e) =>
      e.field === field ||
      e.field.startsWith(`${field}[`) ||
      e.field.startsWith(`${field}.`),
  );
}

export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}
