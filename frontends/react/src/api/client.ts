import createClient from "openapi-fetch";
import type { paths } from "./schema";
import { ApiError, type Problem } from "./problem";

// All calls are same-origin (through the gateway) and carry the session
// cookie; the SPA never sees a token (docs/ARCHITECTURE.md). The explicit
// origin only makes the URLs absolute — required by fetch in the jsdom tests.
export const api = createClient<paths>({
  baseUrl: location.origin,
  credentials: "include",
  // Resolve fetch per call, not at client creation — MSW patches the global
  // after this module is imported in tests.
  fetch: (request) => globalThis.fetch(request),
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
