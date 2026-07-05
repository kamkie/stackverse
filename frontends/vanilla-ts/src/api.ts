import type { FieldError, Problem } from "./types";

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

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
}

export function fieldErrorFor(error: unknown, field: string): FieldError | undefined {
  if (!(error instanceof ApiError)) return undefined;
  return error.fieldErrors.find(
    (entry) =>
      entry.field === field ||
      entry.field.startsWith(`${field}[`) ||
      entry.field.startsWith(`${field}.`),
  );
}

export function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export type QueryValue =
  | string
  | number
  | boolean
  | null
  | undefined
  | readonly (string | number | boolean)[];

export type QueryParams = Record<string, QueryValue>;

export function buildUrl(path: string, query?: QueryParams): string {
  const url = new URL(path, window.location.origin);
  if (!query) return url.toString();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === "") continue;
    if (Array.isArray(value)) {
      for (const item of value) url.searchParams.append(key, String(item));
    } else {
      url.searchParams.set(key, String(value));
    }
  }
  return url.toString();
}

function csrfToken(): string | undefined {
  return document.cookie
    .split("; ")
    .find((pair) => pair.startsWith(`${CSRF_COOKIE}=`))
    ?.slice(CSRF_COOKIE.length + 1);
}

async function parseResponse<T>(response: Response): Promise<T> {
  if (response.status === 204 || response.status === 304) {
    return undefined as T;
  }

  const contentType = response.headers.get("Content-Type") ?? "";
  const body = contentType.includes("json")
    ? ((await response.json()) as unknown)
    : undefined;

  if (!response.ok) {
    throw new ApiError(response.status, body as Problem | undefined);
  }
  return body as T;
}

async function withApiLog<T>(
  method: string,
  path: string,
  operation: () => Promise<{ data: T; status: number }>,
): Promise<T> {
  const started = performance.now();
  try {
    const result = await operation();
    if (import.meta.env.DEV) {
      console.debug(
        `[api] ${method} ${path} -> ${result.status} (${Math.round(performance.now() - started)}ms)`,
      );
    }
    return result.data;
  } catch (error) {
    if (import.meta.env.DEV) {
      const status = error instanceof ApiError ? error.status : "error";
      console.debug(
        `[api] ${method} ${path} -> ${status} (${Math.round(performance.now() - started)}ms)`,
      );
    }
    throw error;
  }
}

export async function apiGet<T>(path: string, query?: QueryParams): Promise<T> {
  return withApiLog("GET", path, async () => {
    const response = await fetch(buildUrl(path, query), {
      credentials: "include",
      headers: { Accept: "application/json" },
    });
    return { data: await parseResponse<T>(response), status: response.status };
  });
}

export async function apiSend<T>(
  method: "POST" | "PUT" | "PATCH" | "DELETE",
  path: string,
  body?: unknown,
): Promise<T> {
  return withApiLog(method, path, async () => {
    const execute = () => {
      const token = csrfToken();
      return fetch(buildUrl(path), {
        method,
        credentials: "include",
        headers: {
          Accept: "application/json",
          ...(body === undefined ? {} : { "Content-Type": "application/json" }),
          ...(token ? { [CSRF_HEADER]: token } : {}),
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
    };

    let response = await execute();
    // Missing/mismatched CSRF gets a fresh cookie on the 403; one retry with
    // the re-read token recovers that contract case and leaves authz 403s intact.
    if (response.status === 403) response = await execute();
    return { data: await parseResponse<T>(response), status: response.status };
  });
}

export async function fetchSession<T>(path: string): Promise<T> {
  return withApiLog("GET", path, async () => {
    const response = await fetch(buildUrl(path), {
      credentials: "include",
      headers: { Accept: "application/json" },
    });
    return { data: await parseResponse<T>(response), status: response.status };
  });
}
