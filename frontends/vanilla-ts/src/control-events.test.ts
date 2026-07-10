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

  it("loads a select filter once when browsers fire input and change", async () => {
    history.replaceState(null, "", "/reports");
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const path = new URL(String(input), window.location.origin).pathname;
      if (path === "/api/v1/messages/bundle") {
        return jsonResponse({ language: "en", messages: {} });
      }
      if (path === "/auth/session") {
        return jsonResponse({ authenticated: true, username: "demo" });
      }
      if (path === "/api/v1/me") {
        return jsonResponse({ username: "demo", roles: [] });
      }
      if (path === "/api/v1/reports") {
        return jsonResponse({
          items: [],
          page: 0,
          size: 20,
          totalItems: 0,
          totalPages: 0,
        });
      }
      return new Response(null, { status: 404 });
    });

    document.body.innerHTML = '<div id="app"></div>';
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
      expect(
        fetchMock.mock.calls.filter(
          ([input]) =>
            new URL(String(input), window.location.origin).pathname ===
            "/api/v1/reports",
        ),
      ).toHaveLength(1);
    });
  });
});
