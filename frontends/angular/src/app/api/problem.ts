import { HttpErrorResponse } from '@angular/common/http';
import type { FieldError, Problem } from './types';

/** A non-2xx API response, carrying the RFC 9457 problem document if present. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem | undefined;

  constructor(status: number, problem?: Problem) {
    super(problem?.detail ?? problem?.title ?? `HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }

  get fieldErrors(): FieldError[] {
    return this.problem?.errors ?? [];
  }
}

/** Normalizes an HttpClient failure into an `ApiError`; rethrows anything else. */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const problem =
      typeof error.error === 'object' && error.error !== null
        ? (error.error as Problem)
        : undefined;
    return new ApiError(error.status, problem);
  }
  throw error;
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
    (e) => e.field === field || e.field.startsWith(`${field}[`) || e.field.startsWith(`${field}.`),
  );
}

export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

export function isConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409;
}

/** Human-readable message for any thrown value — toasts and error states. */
export function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
