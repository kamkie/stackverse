function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("delegated control events", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    document.body.replaceChildren();
    history.replaceState(null, "", "/");
    localStorage.clear();
    sessionStorage.clear();
  });

  it("routes immediate, debounced, and form controls through one event path", async () => {
    history.replaceState(null, "", "/reports");
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input) => {
        const path = new URL(String(input), window.location.origin).pathname;
        if (path === "/api/v1/messages/bundle") {
          return jsonResponse({ language: "en", messages: {} });
        }
        if (path === "/auth/session") {
          return jsonResponse({ authenticated: true, username: "demo" });
        }
        if (path === "/api/v1/me") {
          return jsonResponse({ username: "demo", roles: ["admin"] });
        }
        if (path === "/api/v1/reports" || path === "/api/v1/admin/audit-log") {
          return jsonResponse({
            items: [],
            page: 0,
            size: 20,
            totalItems: 0,
            totalPages: 0,
          });
        }
        if (path === "/api/v2/bookmarks") {
          return jsonResponse({ items: [] });
        }
        return new Response(null, { status: 404 });
      });

    document.body.innerHTML = "<stackverse-app></stackverse-app>";
    await import("./main");
    const status = await vi.waitFor(() => {
      const element = document.querySelector<HTMLSelectElement>(
        'select[data-bind="my-reports-status"]',
      );
      expect(element).not.toBeNull();
      return element!;
    });
    fetchMock.mockClear();

    status.value = "dismissed";
    status.dispatchEvent(new InputEvent("input", { bubbles: true }));
    status.dispatchEvent(new Event("change", { bubbles: true }));

    await vi.waitFor(() => {
      const requests = fetchMock.mock.calls
        .map(([input]) => new URL(String(input), window.location.origin))
        .filter((url) => url.pathname === "/api/v1/reports");
      expect(requests).toHaveLength(1);
      expect(requests[0]?.searchParams.get("status")).toBe("dismissed");
    });

    history.pushState(null, "", "/admin/audit");
    window.dispatchEvent(new PopStateEvent("popstate"));
    const fromDate = await vi.waitFor(() => {
      const element = document.querySelector<HTMLInputElement>(
        'input[data-bind="audit-from"]',
      );
      expect(element).not.toBeNull();
      return element!;
    });
    fetchMock.mockClear();

    fromDate.value = "2026-07-10";
    fromDate.dispatchEvent(new InputEvent("input", { bubbles: true }));
    fromDate.dispatchEvent(new Event("change", { bubbles: true }));

    await vi.waitFor(() => {
      const requests = fetchMock.mock.calls
        .map(([input]) => new URL(String(input), window.location.origin))
        .filter((url) => url.pathname === "/api/v1/admin/audit-log");
      expect(requests).toHaveLength(1);
      expect(requests[0]?.searchParams.get("from")).toBe(
        new Date("2026-07-10T00:00:00").toISOString(),
      );
    });

    history.pushState(null, "", "/feed");
    window.dispatchEvent(new PopStateEvent("popstate"));
    const search = await vi.waitFor(() => {
      const element = document.querySelector<HTMLInputElement>(
        'input[data-bind="feed-q"]',
      );
      expect(element).not.toBeNull();
      return element!;
    });
    fetchMock.mockClear();

    search.value = "first";
    search.dispatchEvent(new InputEvent("input", { bubbles: true }));
    search.value = "latest";
    search.dispatchEvent(new InputEvent("input", { bubbles: true }));
    expect(fetchMock).not.toHaveBeenCalled();

    await vi.waitFor(() => {
      const requests = fetchMock.mock.calls
        .map(([input]) => new URL(String(input), window.location.origin))
        .filter((url) => url.pathname === "/api/v2/bookmarks");
      expect(requests).toHaveLength(1);
      expect(requests[0]?.searchParams.get("q")).toBe("latest");
    });

    const openDialog = document.createElement("button");
    openDialog.dataset.action = APP_ACTIONS.openBookmarkCreate;
    document.body.append(openDialog);
    openDialog.click();
    const visibility = await vi.waitFor(() => {
      const element = document.querySelector<HTMLSelectElement>(
        'form[data-form="bookmark"] select[name="visibility"]',
      );
      expect(element).not.toBeNull();
      return element!;
    });
    visibility.value = "public";
    visibility.dispatchEvent(new InputEvent("input", { bubbles: true }));
    visibility.dispatchEvent(new Event("change", { bubbles: true }));
    window.dispatchEvent(new PopStateEvent("popstate"));

    await vi.waitFor(() => {
      const rerendered = document.querySelector<HTMLSelectElement>(
        'form[data-form="bookmark"] select[name="visibility"]',
      );
      expect(rerendered).not.toBe(visibility);
      expect(rerendered?.value).toBe("public");
    });
  });
});
import { APP_ACTIONS } from "./app-actions";
