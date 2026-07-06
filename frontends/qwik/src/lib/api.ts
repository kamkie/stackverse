export interface Problem {
  title?: string;
  status?: number;
  detail?: string;
  errors?: { field: string; messageKey: string; message: string }[];
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public problem: Problem | null,
  ) {
    super(problem?.detail ?? problem?.title ?? `HTTP ${status}`);
  }
}

export function apiStatus(error: unknown): number | undefined {
  if (error instanceof ApiError) return error.status;
  if (typeof error !== "object" || error === null || !("status" in error)) return undefined;
  const status = (error as { status?: unknown }).status;
  return typeof status === "number" ? status : undefined;
}

export function apiMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function problemFor(error: unknown): Problem | null {
  if (error instanceof ApiError) return error.problem;
  if (typeof error !== "object" || error === null || !("problem" in error)) return null;
  const problem = (error as { problem?: unknown }).problem;
  return typeof problem === "object" && problem !== null ? (problem as Problem) : null;
}

export function fieldErrorFor(error: unknown, field: string): string | undefined {
  return problemFor(error)?.errors?.find((entry) => entry.field === field)?.message;
}

function readCookie(name: string): string | null {
  const prefix = `${name}=`;
  return (
    document.cookie
      .split(";")
      .map((part) => part.trim())
      .find((part) => part.startsWith(prefix))
      ?.slice(prefix.length) ?? null
  );
}

function addCsrfHeader(headers: Headers, path: string, method: string): string | null {
  if (!isMutating(method) || !path.startsWith("/api/")) return null;
  const xsrf = readCookie("XSRF-TOKEN");
  if (xsrf) headers.set("X-XSRF-TOKEN", xsrf);
  return xsrf;
}

function isMutating(method: string): boolean {
  return ["POST", "PUT", "PATCH", "DELETE"].includes(method.toUpperCase());
}

export function queryString(params: Record<string, unknown>): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "") continue;
    if (Array.isArray(value)) {
      for (const item of value) query.append(key, String(item));
    } else {
      query.set(key, String(value));
    }
  }
  const serialized = query.toString();
  return serialized ? `?${serialized}` : "";
}

async function parseProblem(response: Response): Promise<Problem | null> {
  const type = response.headers.get("content-type") ?? "";
  if (!type.includes("json")) return null;
  try {
    return (await response.json()) as Problem;
  } catch {
    return null;
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  if (init.body !== undefined && !headers.has("content-type")) {
    headers.set("content-type", "application/json");
  }
  const xsrf = addCsrfHeader(headers, path, method);
  let response = await fetch(new URL(path, location.origin), {
    ...init,
    method,
    headers,
  });
  const freshXsrf = readCookie("XSRF-TOKEN");
  if (
    response.status === 403 &&
    isMutating(method) &&
    path.startsWith("/api/") &&
    freshXsrf &&
    freshXsrf !== xsrf
  ) {
    const retryHeaders = new Headers(headers);
    retryHeaders.set("X-XSRF-TOKEN", freshXsrf);
    response = await fetch(new URL(path, location.origin), {
      ...init,
      method,
      headers: retryHeaders,
    });
  }
  if (response.status === 204 || response.status === 304) {
    return undefined as T;
  }
  if (!response.ok) {
    throw new ApiError(response.status, await parseProblem(response));
  }
  return (await response.json()) as T;
}

export function jsonBody(body: unknown): Pick<RequestInit, "body"> {
  return { body: JSON.stringify(body) };
}
