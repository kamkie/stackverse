import { ApiError } from "./api";
import { startAppController } from "./app-controller";
import { state } from "./app-state";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function installFetchMock() {
  return vi
    .spyOn(globalThis, "fetch")
    .mockImplementation(async (input, init) => {
      const url = new URL(String(input), window.location.origin);
      const method = init?.method ?? "GET";

      if (url.pathname === "/api/v1/messages/bundle") {
        return jsonResponse({
          language: "en",
          messages: {
            "ui.nav.public-feed": "Public feed",
            "ui.bookmarks.empty": "No bookmarks yet.",
          },
        });
      }
      if (url.pathname === "/auth/session") {
        return jsonResponse({ authenticated: false });
      }
      if (url.pathname === "/api/v2/bookmarks") {
        return jsonResponse({ items: [] });
      }
      if (url.pathname === "/api/v1/bookmarks" && method === "POST") {
        return jsonResponse(
          {
            title: "Validation failed",
            status: 422,
            errors: [
              {
                field: "url",
                message: "must be a valid URL",
                messageKey: "validation.url.invalid",
              },
            ],
          },
          422,
        );
      }
      if (url.pathname === "/auth/logout" && method === "POST") {
        return new Response(null, { status: 204 });
      }
      return new Response(null, { status: 404 });
    });
}

describe("app controller event boundary", () => {
  let stopController: (() => void) | undefined;

  beforeEach(() => {
    history.replaceState(null, "", "/feed");
    document.body.innerHTML = '<div id="app"></div>';
    localStorage.clear();
    sessionStorage.clear();
    state.session = null;
    state.me = null;
    state.renderVersion = 0;
    state.dialog = null;
    state.toasts = [];
    state.nextToastId = 0;
    state.bookmarks = { q: "", tags: [], pages: [] };
    state.feed = { q: "", tags: [], pages: [] };
  });

  afterEach(() => {
    stopController?.();
    stopController = undefined;
    vi.restoreAllMocks();
  });

  it("preserves submitted values and problem details after validation fails", async () => {
    const fetchMock = installFetchMock();
    const root = document.querySelector<HTMLElement>("#app");
    expect(root).not.toBeNull();
    stopController = await startAppController(root!, {
      enableDevInstrumentation: false,
    });

    state.dialog = { kind: "bookmark-form", mode: "create" };
    const form = document.createElement("form");
    form.dataset.form = "bookmark";
    form.innerHTML = `
      <input name="url" value="not-a-url">
      <input name="title" value="Broken bookmark">
      <input name="tags" value="test, invalid">
      <select name="visibility"><option value="private" selected>Private</option></select>
    `;
    root!.append(form);
    form.dispatchEvent(
      new SubmitEvent("submit", { bubbles: true, cancelable: true }),
    );

    await vi.waitFor(() => {
      expect(state.dialog?.kind).toBe("bookmark-form");
      if (state.dialog?.kind !== "bookmark-form") return;
      expect(state.dialog.values).toMatchObject({
        url: "not-a-url",
        title: "Broken bookmark",
        tags: "test, invalid",
        visibility: "private",
      });
      expect(state.dialog.error).toBeInstanceOf(ApiError);
      expect((state.dialog.error as ApiError).status).toBe(422);
      expect(root!.textContent).toContain("must be a valid URL");
    });

    const createCall = fetchMock.mock.calls.find(([input, init]) => {
      const url = new URL(String(input), window.location.origin);
      return url.pathname === "/api/v1/bookmarks" && init?.method === "POST";
    });
    expect(createCall).toBeDefined();
    expect(JSON.parse(String(createCall?.[1]?.body))).toMatchObject({
      url: "not-a-url",
      title: "Broken bookmark",
      tags: ["test", "invalid"],
      visibility: "private",
    });
  });

  it("delegates logout clicks and clears authenticated state", async () => {
    const fetchMock = installFetchMock();
    const root = document.querySelector<HTMLElement>("#app");
    expect(root).not.toBeNull();
    stopController = await startAppController(root!, {
      enableDevInstrumentation: false,
    });
    state.session = { authenticated: true, username: "demo" };

    const logout = document.createElement("button");
    logout.dataset.action = "logout";
    root!.append(logout);
    logout.click();

    await vi.waitFor(() => {
      expect(state.session).toEqual({ authenticated: false });
      expect(
        fetchMock.mock.calls.some(([input, init]) => {
          const url = new URL(String(input), window.location.origin);
          return url.pathname === "/auth/logout" && init?.method === "POST";
        }),
      ).toBe(true);
    });
  });
});
