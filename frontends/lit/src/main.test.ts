import { fixture } from "@open-wc/testing-helpers";
import { html } from "lit";

describe("stackverse-app custom element", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  it("renders the app shell in light DOM", async () => {
    vi.spyOn(console, "debug").mockImplementation(() => undefined);
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input) => {
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
    const host = await fixture<HTMLElement>(
      html`<stackverse-app></stackverse-app>`,
    );
    expect(host?.shadowRoot).toBeNull();
    await vi.waitFor(() => {
      expect(host?.querySelector(".sv-app")).not.toBeNull();
      expect(host?.textContent).toContain("Public feed");
    });

    const requestPaths = fetchMock.mock.calls.map(
      ([input]) => new URL(String(input), window.location.origin).pathname,
    );
    expect(
      requestPaths.filter((path) => path === "/auth/session"),
    ).toHaveLength(1);
    expect(
      requestPaths.filter((path) => path === "/api/v1/messages/bundle"),
    ).toHaveLength(1);
    expect(
      requestPaths.filter((path) => path === "/api/v2/bookmarks"),
    ).toHaveLength(1);
  });
});
