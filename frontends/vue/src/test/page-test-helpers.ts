import { flushPromises } from "@vue/test-utils";
import { nextTick } from "vue";
import { vi } from "vitest";

export type FetchHandler = (request: Request) => Response | Promise<Response>;

export function stubFetch(handler: FetchHandler): Request[] {
  const requests: Request[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      requests.push(request.clone());
      return handler(request);
    }),
  );
  return requests;
}

export async function settle(): Promise<void> {
  await flushPromises();
  await nextTick();
  await flushPromises();
}

export async function seedMessages(messages: Record<string, string> = {}): Promise<void> {
  const { bundle } = await import("../i18n/i18n");
  bundle.value = { language: "en", messages };
}

export function page<T>(items: T[], totalPages = 1) {
  return {
    items,
    page: 0,
    size: 20,
    totalItems: items.length,
    totalPages,
  };
}

export function problem(status: number, detail: string, errors?: unknown[]): Response {
  return Response.json(
    {
      title: status === 400 ? "Validation failed" : "Request failed",
      status,
      detail,
      ...(errors ? { errors } : {}),
    },
    { status },
  );
}
