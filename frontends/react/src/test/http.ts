export interface ApiRequestOptions {
  method?: string;
  body?: unknown;
  headers?: HeadersInit;
}

/** Small raw-HTTP helper for exercising the contract-derived MSW server. */
export async function apiRequest(
  path: string,
  options: ApiRequestOptions = {},
): Promise<Response> {
  const headers = new Headers(options.headers);
  const init: RequestInit = {
    method: options.method ?? "GET",
    headers,
  };
  if (options.body !== undefined) {
    headers.set("content-type", "application/json");
    init.body = JSON.stringify(options.body);
  }
  return fetch(new URL(path, location.origin), init);
}

export async function responseJson<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}
