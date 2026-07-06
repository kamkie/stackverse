describe("stackverse-app custom element", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = "";
    localStorage.clear();
    sessionStorage.clear();
  });

  it("renders the app shell in light DOM", async () => {
    document.body.innerHTML = "<stackverse-app></stackverse-app>";
    vi.spyOn(console, "debug").mockImplementation(() => undefined);
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = new URL(String(input), window.location.origin);
      if (url.pathname === "/api/v1/messages/bundle") {
        return new Response(
          JSON.stringify({
            language: "en",
            messages: {
              "ui.app.title": "Stackverse",
              "ui.nav.public-feed": "Public feed",
              "ui.bookmarks.empty": "No bookmarks yet.",
              "ui.bookmarks.search.placeholder": "Search bookmarks",
              "ui.action.login": "Log in",
            },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        );
      }
      if (url.pathname === "/auth/session") {
        return new Response(JSON.stringify({ authenticated: false }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (url.pathname === "/api/v2/bookmarks") {
        return new Response(JSON.stringify({ items: [] }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      return new Response(null, { status: 404 });
    });

    await import("./main");

    const host = document.querySelector("stackverse-app");
    expect(host?.shadowRoot).toBeNull();
    await vi.waitFor(() => {
      expect(host?.querySelector(".sv-app")).not.toBeNull();
      expect(host?.textContent).toContain("Public feed");
    });
  });
});
