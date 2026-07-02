import createClient from "openapi-fetch";
import type { paths } from "./schema";
import { ApiError, type Problem } from "./problem";

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

function csrfToken(): string | undefined {
  return document.cookie
    .split("; ")
    .find((pair) => pair.startsWith(`${CSRF_COOKIE}=`))
    ?.slice(CSRF_COOKIE.length + 1);
}

// State-changing requests must echo the gateway's double-submit cookie as a
// header (frontends/README.md). The token is read per request, so a 403 can
// only mean the browser had no cookie yet — the 403 response itself carries a
// fresh one, so a single retry with the re-read value recovers that case.
// In mock mode (MSW) the cookie never exists and the header is simply absent.
async function csrfFetch(request: Request): Promise<Response> {
  const method = request.method.toUpperCase();
  if (method === "GET" || method === "HEAD" || method === "OPTIONS") {
    return globalThis.fetch(request);
  }
  const token = csrfToken();
  const attempt = request.clone();
  if (token) attempt.headers.set(CSRF_HEADER, token);
  const response = await globalThis.fetch(attempt);
  const fresh = csrfToken();
  if (response.status !== 403 || !fresh || fresh === token) return response;
  request.headers.set(CSRF_HEADER, fresh);
  return globalThis.fetch(request);
}

// All calls are same-origin (through the gateway) and carry the session
// cookie; the SPA never sees a token (docs/ARCHITECTURE.md). The explicit
// origin only makes the URLs absolute — required by fetch in the jsdom tests.
export const api = createClient<paths>({
  baseUrl: location.origin,
  credentials: "include",
  // Resolve fetch per call, not at client creation — MSW patches the global
  // after this module is imported in tests.
  fetch: csrfFetch,
});

/**
 * Unwraps an openapi-fetch result: returns the data on success, throws an
 * `ApiError` carrying the RFC 9457 problem document otherwise.
 */
export function unwrap<T>(result: {
  data?: T;
  error?: unknown;
  response: Response;
}): T {
  if (!result.response.ok) {
    throw new ApiError(result.response.status, result.error as Problem | undefined);
  }
  return result.data as T;
}
