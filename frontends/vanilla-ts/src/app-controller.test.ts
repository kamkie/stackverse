import { ApiError } from "./api";
import { startAppController } from "./app-controller";
import { state } from "./app-state";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
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

  it("starts a replacement controller from clean application state", async () => {
    installFetchMock();
    const firstRoot = document.querySelector<HTMLElement>("#app");
    expect(firstRoot).not.toBeNull();
    stopController = await startAppController(firstRoot!, {
      enableDevInstrumentation: false,
    });

    state.dialog = { kind: "bookmark-form", mode: "create" };
    state.toasts = [{ id: 7, message: "stale", variant: "danger" }];
    state.nextToastId = 8;
    state.bookmarks.q = "old bookmark filter";
    state.adminReports.status = "dismissed";
    state.adminReports.page = 4;
    state.users.q = "old user filter";
    state.audit.actor = "old actor";
    state.messages.language = "pl";

    stopController();
    stopController = undefined;
    const replacementRoot = document.createElement("div");
    document.body.replaceChildren(replacementRoot);
    stopController = await startAppController(replacementRoot, {
      enableDevInstrumentation: false,
    });

    expect(state.dialog).toBeNull();
    expect(state.toasts).toEqual([]);
    expect(state.nextToastId).toBe(0);
    expect(state.bookmarks.q).toBe("");
    expect(state.adminReports).toMatchObject({ status: "open", page: 0 });
    expect(state.users.q).toBe("");
    expect(state.audit.actor).toBe("");
    expect(state.messages.language).toBe("");
  });

  it("rejects a disposed controller's delayed render after restart", async () => {
    const staleStats = deferred<Response>();
    const fetchMock = installFetchMock();
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input), window.location.origin);
      if (url.pathname === "/api/v1/admin/stats") return staleStats.promise;
      const method = init?.method ?? "GET";
      if (url.pathname === "/api/v1/messages/bundle") {
        return jsonResponse({ language: "en", messages: {} });
      }
      if (url.pathname === "/auth/session") {
        return jsonResponse({ authenticated: false });
      }
      if (url.pathname === "/api/v2/bookmarks") {
        return jsonResponse({ items: [] });
      }
      if (url.pathname === "/auth/logout" && method === "POST") {
        return new Response(null, { status: 204 });
      }
      return new Response(null, { status: 404 });
    });

    const firstRoot = document.querySelector<HTMLElement>("#app");
    expect(firstRoot).not.toBeNull();
    stopController = await startAppController(firstRoot!, {
      enableDevInstrumentation: false,
    });
    state.session = { authenticated: true, username: "admin" };
    state.me = { username: "admin", roles: ["admin"] };
    history.pushState(null, "", "/admin");
    window.dispatchEvent(new PopStateEvent("popstate"));
    await vi.waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([input]) =>
            new URL(String(input), window.location.origin).pathname ===
            "/api/v1/admin/stats",
        ),
      ).toBe(true);
    });

    stopController();
    stopController = undefined;
    history.replaceState(null, "", "/feed");
    const replacementRoot = document.createElement("div");
    document.body.replaceChildren(replacementRoot);
    stopController = await startAppController(replacementRoot, {
      enableDevInstrumentation: false,
    });
    window.dispatchEvent(new PopStateEvent("popstate"));
    await vi.waitFor(() => {
      expect(state.renderVersion).toBe(2);
      expect(replacementRoot.querySelector(".sv-loading")).toBeNull();
    });

    const staleResponse = jsonResponse({
      totals: {
        users: 999,
        bookmarks: 0,
        publicBookmarks: 0,
        hiddenBookmarks: 0,
        openReports: 0,
      },
      daily: [],
      topTags: [],
    });
    const jsonSpy = vi.spyOn(staleResponse, "json");
    staleStats.resolve(staleResponse);
    await vi.waitFor(() => expect(jsonSpy).toHaveBeenCalledOnce());
    await jsonSpy.mock.results[0]!.value;
    await Promise.resolve();

    expect(window.location.pathname).toBe("/feed");
    expect(replacementRoot.textContent).not.toContain("999");
  });
});
