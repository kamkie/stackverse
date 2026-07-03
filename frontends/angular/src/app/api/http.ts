import { firstValueFrom, type Observable } from 'rxjs';
import { toApiError } from './problem';

/**
 * Awaits an HttpClient request, converting failures into `ApiError`s carrying
 * the RFC 9457 problem document — the app's uniform error currency.
 */
export async function call<T>(request: Observable<T>): Promise<T> {
  try {
    return await firstValueFrom(request);
  } catch (error) {
    throw toApiError(error);
  }
}

/** Builds HttpClient query params, dropping empty/undefined values. */
export function params(
  entries: Record<string, string | number | readonly string[] | undefined>,
): Record<string, string | number | readonly string[]> {
  const out: Record<string, string | number | readonly string[]> = {};
  for (const [key, value] of Object.entries(entries)) {
    if (value === undefined || value === '') continue;
    if (Array.isArray(value) && value.length === 0) continue;
    out[key] = value;
  }
  return out;
}
