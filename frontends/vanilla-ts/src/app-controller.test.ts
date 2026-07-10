import { ApiError } from "./api";
import { startAppController } from "./app-controller";
import { i18n, REPORTED_STORAGE_KEY, state } from "./app-state";

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
            "ui.action.delete": "Delete",
            "ui.action.withdraw": "Withdraw",
            "ui.action.logout": "Log out",
            "ui.toast.message-deleted": "Message deleted.",
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
    i18n.lang = null;
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

  it("renders logout transport failures without clearing the session", async () => {
    const fetchMock = installFetchMock();
    const defaultFetch = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input), window.location.origin);
      if (url.pathname === "/auth/logout" && init?.method === "POST") {
        throw new TypeError("Failed to fetch");
      }
      return defaultFetch(input, init);
    });
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
      expect(state.session).toEqual({ authenticated: true, username: "demo" });
      expect(
        root!.querySelector<HTMLElement>(".sv-toast.sv-toast--danger")
          ?.textContent,
      ).toBe("Log out: Failed to fetch");
    });
  });

  it("renders a destructive 4xx as feedback and preserves its dialog", async () => {
    const fetchMock = installFetchMock();
    const defaultFetch = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input), window.location.origin);
      if (
        url.pathname === "/api/v1/bookmarks/bookmark-1" &&
        init?.method === "DELETE"
      ) {
        return jsonResponse(
          {
            title: "Conflict",
            status: 409,
            detail: "Bookmark cannot be deleted.",
          },
          409,
        );
      }
      return defaultFetch(input, init);
    });
    const root = document.querySelector<HTMLElement>("#app");
    expect(root).not.toBeNull();
    stopController = await startAppController(root!, {
      enableDevInstrumentation: false,
    });

    const dialog = {
      kind: "delete-bookmark" as const,
      bookmark: {
        id: "bookmark-1",
        owner: "demo",
        url: "https://example.com",
        title: "Example",
        tags: [],
        visibility: "private" as const,
        status: "active" as const,
        createdAt: "2026-07-10T00:00:00Z",
        updatedAt: "2026-07-10T00:00:00Z",
      },
    };
    state.dialog = dialog;
    const confirm = document.createElement("button");
    confirm.dataset.action = "confirm-bookmark-delete";
    root!.append(confirm);
    confirm.click();

    await vi.waitFor(() => {
      expect(state.dialog).toBe(dialog);
      const toast = root!.querySelector<HTMLElement>(
        ".sv-toast.sv-toast--danger",
      );
      expect(toast?.textContent).toBe("Delete: Bookmark cannot be deleted.");
      expect(root!.querySelector("dialog.sv-dialog")).not.toBeNull();
    });
  });

  it("renders a destructive network failure and preserves its dialog", async () => {
    const fetchMock = installFetchMock();
    const defaultFetch = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input), window.location.origin);
      if (
        url.pathname === "/api/v1/reports/report-1" &&
        init?.method === "DELETE"
      ) {
        throw new TypeError("Failed to fetch");
      }
      return defaultFetch(input, init);
    });
    const root = document.querySelector<HTMLElement>("#app");
    expect(root).not.toBeNull();
    stopController = await startAppController(root!, {
      enableDevInstrumentation: false,
    });

    const dialog = {
      kind: "withdraw-report" as const,
      report: {
        id: "report-1",
        bookmarkId: "bookmark-1",
        reporter: "demo",
        reason: "spam" as const,
        status: "open" as const,
        createdAt: "2026-07-10T00:00:00Z",
      },
    };
    state.dialog = dialog;
    const confirm = document.createElement("button");
    confirm.dataset.action = "confirm-report-withdraw";
    root!.append(confirm);
    confirm.click();

    await vi.waitFor(() => {
      expect(state.dialog).toBe(dialog);
      const toast = root!.querySelector<HTMLElement>(
        ".sv-toast.sv-toast--danger",
      );
      expect(toast?.textContent).toBe("Withdraw: Failed to fetch");
      expect(root!.querySelector("dialog.sv-dialog")).not.toBeNull();
    });
  });

  it("keeps a successful message deletion when bundle refresh fails", async () => {
    const fetchMock = installFetchMock();
    const defaultFetch = fetchMock.getMockImplementation()!;
    let bundleRequests = 0;
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input), window.location.origin);
      if (url.pathname === "/api/v1/messages/bundle") {
        bundleRequests += 1;
        if (bundleRequests > 1) throw new TypeError("Failed to fetch");
      }
      if (
        url.pathname === "/api/v1/messages/message-1" &&
        init?.method === "DELETE"
      ) {
        return new Response(null, { status: 204 });
      }
      return defaultFetch(input, init);
    });
    const root = document.querySelector<HTMLElement>("#app");
    expect(root).not.toBeNull();
    stopController = await startAppController(root!, {
      enableDevInstrumentation: false,
    });

    state.dialog = {
      kind: "delete-message",
      message: {
        id: "message-1",
        key: "ui.test.message",
        language: "en",
        text: "Test",
        createdAt: "2026-07-10T00:00:00Z",
        updatedAt: "2026-07-10T00:00:00Z",
      },
    };
    const confirm = document.createElement("button");
    confirm.dataset.action = "confirm-message-delete";
    root!.append(confirm);
    confirm.click();

    await vi.waitFor(() => {
      expect(bundleRequests).toBe(2);
      expect(state.dialog).toBeNull();
      expect(
        root!.querySelector<HTMLElement>(".sv-toast.sv-toast--success")
          ?.textContent,
      ).toBe("Message deleted.");
      expect(root!.querySelector(".sv-toast--danger")).toBeNull();
    });
  });

  it("keeps a successful message deletion when bundle decoding fails", async () => {
    const fetchMock = installFetchMock();
    const defaultFetch = fetchMock.getMockImplementation()!;
    let bundleRequests = 0;
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input), window.location.origin);
      if (url.pathname === "/api/v1/messages/bundle") {
        bundleRequests += 1;
        if (bundleRequests > 1) {
          return new Response("{", {
            status: 200,
            headers: { "Content-Type": "application/json" },
          });
        }
      }
      if (
        url.pathname === "/api/v1/messages/message-1" &&
        init?.method === "DELETE"
      ) {
        return new Response(null, { status: 204 });
      }
      return defaultFetch(input, init);
    });
    const root = document.querySelector<HTMLElement>("#app");
    expect(root).not.toBeNull();
    stopController = await startAppController(root!, {
      enableDevInstrumentation: false,
    });

    state.dialog = {
      kind: "delete-message",
      message: {
        id: "message-1",
        key: "ui.test.message",
        language: "en",
        text: "Test",
        createdAt: "2026-07-10T00:00:00Z",
        updatedAt: "2026-07-10T00:00:00Z",
      },
    };
    const confirm = document.createElement("button");
    confirm.dataset.action = "confirm-message-delete";
    root!.append(confirm);
    confirm.click();

    await vi.waitFor(() => {
      expect(bundleRequests).toBe(2);
      expect(state.dialog).toBeNull();
      expect(
        root!.querySelector<HTMLElement>(".sv-toast.sv-toast--success")
          ?.textContent,
      ).toBe("Message deleted.");
      expect(root!.querySelector(".sv-toast--danger")).toBeNull();
    });
  });

  it("closes a committed message deletion before its bundle refresh completes", async () => {
    const refresh = deferred<Response>();
    const fetchMock = installFetchMock();
    const defaultFetch = fetchMock.getMockImplementation()!;
    let bundleRequests = 0;
    let deleteRequests = 0;
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input), window.location.origin);
      if (url.pathname === "/api/v1/messages/bundle") {
        bundleRequests += 1;
        if (bundleRequests === 2) return refresh.promise;
      }
      if (
        url.pathname === "/api/v1/messages/message-1" &&
        init?.method === "DELETE"
      ) {
        deleteRequests += 1;
        return new Response(null, { status: 204 });
      }
      return defaultFetch(input, init);
    });
    const root = document.querySelector<HTMLElement>("#app");
    expect(root).not.toBeNull();
    stopController = await startAppController(root!, {
      enableDevInstrumentation: false,
    });

    state.dialog = {
      kind: "delete-message",
      message: {
        id: "message-1",
        key: "ui.test.message",
        language: "en",
        text: "Test",
        createdAt: "2026-07-10T00:00:00Z",
        updatedAt: "2026-07-10T00:00:00Z",
      },
    };
    const confirm = document.createElement("button");
    confirm.dataset.action = "confirm-message-delete";
    root!.append(confirm);
    confirm.click();

    await vi.waitFor(() => {
      expect(bundleRequests).toBe(2);
      expect(deleteRequests).toBe(1);
      expect(state.dialog).toBeNull();
      expect(
        root!.querySelector<HTMLElement>(".sv-toast.sv-toast--success")
          ?.textContent,
      ).toBe("Message deleted.");
      expect(root!.querySelector("dialog.sv-dialog")).toBeNull();
    });

    confirm.click();
    await new Promise<void>((resolve) => window.setTimeout(resolve, 0));
    expect(deleteRequests).toBe(1);

    refresh.resolve(
      jsonResponse({
        language: "en",
        messages: {
          "ui.app.title": "Refreshed title",
          "ui.nav.public-feed": "Public feed",
          "ui.bookmarks.empty": "No bookmarks yet.",
          "ui.toast.message-deleted": "Message deleted.",
        },
      }),
    );
    await vi.waitFor(() => expect(document.title).toBe("Refreshed title"));
    expect(deleteRequests).toBe(1);
    expect(state.dialog).toBeNull();
  });

  it("prevents a stopped controller's bundle refresh from overwriting its replacement", async () => {
    const staleBundle = deferred<Response>();
    const fetchMock = installFetchMock();
    const defaultFetch = fetchMock.getMockImplementation()!;
    let bundleRequests = 0;
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input), window.location.origin);
      if (url.pathname === "/api/v1/messages/bundle") {
        bundleRequests += 1;
        if (bundleRequests === 1) {
          return jsonResponse({
            language: "en",
            messages: {
              "ui.app.title": "Initial title",
              "ui.nav.public-feed": "Public feed",
              "ui.bookmarks.empty": "No bookmarks yet.",
              "ui.toast.message-deleted": "Message deleted.",
            },
          });
        }
        if (bundleRequests === 2) return staleBundle.promise;
        if (bundleRequests === 3) {
          return jsonResponse({
            language: "pl",
            messages: {
              "ui.app.title": "Replacement title",
              "ui.nav.public-feed": "Publiczne",
              "ui.bookmarks.empty": "Brak zakladek.",
            },
          });
        }
        throw new Error("Unexpected bundle request");
      }
      if (
        url.pathname === "/api/v1/messages/message-1" &&
        init?.method === "DELETE"
      ) {
        return new Response(null, { status: 204 });
      }
      return defaultFetch(input, init);
    });
    i18n.lang = null;
    const firstRoot = document.querySelector<HTMLElement>("#app");
    expect(firstRoot).not.toBeNull();
    stopController = await startAppController(firstRoot!, {
      enableDevInstrumentation: false,
    });
    state.dialog = {
      kind: "delete-message",
      message: {
        id: "message-1",
        key: "ui.test.message",
        language: "en",
        text: "Test",
        createdAt: "2026-07-10T00:00:00Z",
        updatedAt: "2026-07-10T00:00:00Z",
      },
    };
    const confirm = document.createElement("button");
    confirm.dataset.action = "confirm-message-delete";
    firstRoot!.append(confirm);
    confirm.click();
    await vi.waitFor(() => expect(bundleRequests).toBe(2));

    stopController();
    stopController = undefined;
    i18n.lang = "pl";
    const replacementRoot = document.createElement("div");
    document.body.replaceChildren(replacementRoot);
    stopController = await startAppController(replacementRoot, {
      enableDevInstrumentation: false,
    });
    const replacementDialog = {
      kind: "delete-message" as const,
      message: {
        id: "message-2",
        key: "ui.test.replacement",
        language: "pl",
        text: "Replacement",
        createdAt: "2026-07-10T00:00:00Z",
        updatedAt: "2026-07-10T00:00:00Z",
      },
    };
    state.dialog = replacementDialog;
    const replacementRenderVersion = state.renderVersion;
    const replacementCache = localStorage.getItem("stackverse.bundle.pl");

    staleBundle.resolve(
      jsonResponse({
        language: "en",
        messages: { "ui.app.title": "Stale title" },
      }),
    );
    await new Promise<void>((resolve) => window.setTimeout(resolve, 0));

    expect(bundleRequests).toBe(3);
    expect(i18n.resolvedLanguage).toBe("pl");
    expect(i18n.t("ui.app.title")).toBe("Replacement title");
    expect(document.documentElement.lang).toBe("pl");
    expect(document.title).toBe("Replacement title");
    expect(localStorage.getItem("stackverse.bundle.pl")).toBe(replacementCache);
    const originalCache = JSON.parse(
      localStorage.getItem("stackverse.bundle.auto") ?? "null",
    ) as { bundle?: { messages?: Record<string, string> } } | null;
    expect(originalCache?.bundle?.messages?.["ui.app.title"]).toBe(
      "Initial title",
    );
    expect(state.dialog).toBe(replacementDialog);
    expect(state.toasts).toEqual([]);
    expect(state.renderVersion).toBe(replacementRenderVersion);
  });

  it("ignores a stopped controller's delayed destructive success", async () => {
    const staleDelete = deferred<Response>();
    const fetchMock = installFetchMock();
    const defaultFetch = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input), window.location.origin);
      if (
        url.pathname === "/api/v1/reports/report-1" &&
        init?.method === "DELETE"
      ) {
        return staleDelete.promise;
      }
      return defaultFetch(input, init);
    });
    const firstRoot = document.querySelector<HTMLElement>("#app");
    expect(firstRoot).not.toBeNull();
    stopController = await startAppController(firstRoot!, {
      enableDevInstrumentation: false,
    });
    state.dialog = {
      kind: "withdraw-report",
      report: {
        id: "report-1",
        bookmarkId: "bookmark-1",
        reporter: "demo",
        reason: "spam",
        status: "open",
        createdAt: "2026-07-10T00:00:00Z",
      },
    };
    const confirm = document.createElement("button");
    confirm.dataset.action = "confirm-report-withdraw";
    firstRoot!.append(confirm);
    confirm.click();
    await vi.waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) =>
            new URL(String(input), window.location.origin).pathname ===
              "/api/v1/reports/report-1" && init?.method === "DELETE",
        ),
      ).toBe(true);
    });

    stopController();
    stopController = undefined;
    const replacementRoot = document.createElement("div");
    document.body.replaceChildren(replacementRoot);
    stopController = await startAppController(replacementRoot, {
      enableDevInstrumentation: false,
    });
    const replacementDialog = {
      kind: "withdraw-report" as const,
      report: {
        id: "report-2",
        bookmarkId: "bookmark-2",
        reporter: "demo",
        reason: "other" as const,
        status: "open" as const,
        createdAt: "2026-07-10T00:00:00Z",
      },
    };
    state.dialog = replacementDialog;
    sessionStorage.setItem(
      REPORTED_STORAGE_KEY,
      JSON.stringify(["bookmark-1", "bookmark-2"]),
    );
    const replacementRenderVersion = state.renderVersion;

    staleDelete.resolve(new Response(null, { status: 204 }));
    await new Promise<void>((resolve) => window.setTimeout(resolve, 0));

    expect(state.dialog).toBe(replacementDialog);
    expect(state.toasts).toEqual([]);
    expect(state.renderVersion).toBe(replacementRenderVersion);
    expect(
      JSON.parse(sessionStorage.getItem(REPORTED_STORAGE_KEY) ?? "[]"),
    ).toEqual(["bookmark-1", "bookmark-2"]);
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

  it("resolves a feed report action from the feed cache when bookmark ids overlap", async () => {
    installFetchMock();
    const root = document.querySelector<HTMLElement>("#app");
    expect(root).not.toBeNull();
    stopController = await startAppController(root!, {
      enableDevInstrumentation: false,
    });
    state.session = { authenticated: true, username: "demo" };
    const personalBookmark = {
      id: "shared-bookmark",
      owner: "demo",
      url: "https://personal.example",
      title: "Older personal title",
      tags: [],
      visibility: "public" as const,
      status: "active" as const,
      createdAt: "2026-07-09T00:00:00Z",
      updatedAt: "2026-07-09T00:00:00Z",
    };
    const feedBookmark = {
      ...personalBookmark,
      url: "https://feed.example",
      title: "Current feed title",
      updatedAt: "2026-07-10T00:00:00Z",
    };
    state.bookmarks.pages = [{ items: [personalBookmark] }];
    state.bookmarks.loadedGeneration = state.bookmarks.generation;
    state.feed.pages = [{ items: [feedBookmark] }];
    state.feed.loadedGeneration = state.feed.generation;

    window.dispatchEvent(new PopStateEvent("popstate"));
    const report = await vi.waitFor(() => {
      expect(root!.textContent).toContain("Current feed title");
      expect(root!.textContent).not.toContain("Older personal title");
      const button = root!.querySelector<HTMLButtonElement>(
        '[data-action="open-report"][data-id="shared-bookmark"]',
      );
      expect(button).not.toBeNull();
      return button!;
    });
    report.click();

    await vi.waitFor(() => {
      expect(state.dialog?.kind).toBe("report-bookmark");
      if (state.dialog?.kind !== "report-bookmark") return;
      expect(state.dialog.bookmark).toBe(feedBookmark);
      expect(state.dialog.bookmark.title).toBe("Current feed title");
      expect(root!.querySelector(".sv-dialog-title")?.textContent).toContain(
        "Current feed title",
      );
    });
  });

  it("publishes only the current out-of-order page and resolves its row actions", async () => {
    const staleUsers = deferred<Response>();
    const currentUsers = deferred<Response>();
    const fetchMock = installFetchMock();
    const defaultFetch = fetchMock.getMockImplementation()!;
    let currentPageRequests = 0;
    const currentUser = {
      username: "current-user",
      firstSeen: "2026-07-10T00:00:00Z",
      lastSeen: "2026-07-10T00:00:00Z",
      status: "active",
      bookmarkCount: 2,
    } as const;
    const staleUser = {
      username: "stale-user",
      firstSeen: "2026-07-09T00:00:00Z",
      lastSeen: "2026-07-09T00:00:00Z",
      status: "active",
      bookmarkCount: 1,
    } as const;
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input), window.location.origin);
      if (url.pathname === "/api/v1/admin/users") {
        const page = url.searchParams.get("page");
        if (page === "0") return staleUsers.promise;
        if (page === "1") {
          currentPageRequests += 1;
          if (currentPageRequests === 1) return currentUsers.promise;
          return jsonResponse({
            items: [currentUser],
            page: 1,
            size: 20,
            totalItems: 2,
            totalPages: 2,
          });
        }
      }
      return defaultFetch(input, init);
    });

    const root = document.querySelector<HTMLElement>("#app");
    expect(root).not.toBeNull();
    stopController = await startAppController(root!, {
      enableDevInstrumentation: false,
    });
    state.session = { authenticated: true, username: "admin" };
    state.me = { username: "admin", roles: ["admin"] };
    history.pushState(null, "", "/admin/users");
    window.dispatchEvent(new PopStateEvent("popstate"));
    await vi.waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([input]) =>
            new URL(String(input), window.location.origin).pathname ===
              "/api/v1/admin/users" &&
            new URL(String(input), window.location.origin).searchParams.get(
              "page",
            ) === "0",
        ),
      ).toBe(true);
    });

    const nextPage = document.createElement("button");
    nextPage.dataset.action = "page";
    nextPage.dataset.bind = "users";
    nextPage.dataset.page = "1";
    root!.append(nextPage);
    nextPage.click();
    await vi.waitFor(() => expect(currentPageRequests).toBe(1));

    currentUsers.resolve(
      jsonResponse({
        items: [currentUser],
        page: 1,
        size: 20,
        totalItems: 2,
        totalPages: 2,
      }),
    );
    await vi.waitFor(() => {
      expect(state.users.items).toEqual([currentUser]);
      expect(root!.textContent).toContain("current-user");
      expect(root!.textContent).not.toContain("stale-user");
    });

    const staleResponse = jsonResponse({
      items: [staleUser],
      page: 0,
      size: 20,
      totalItems: 2,
      totalPages: 2,
    });
    const staleJson = vi.spyOn(staleResponse, "json");
    staleUsers.resolve(staleResponse);
    await vi.waitFor(() => expect(staleJson).toHaveBeenCalledOnce());
    await staleJson.mock.results[0]!.value;
    await Promise.resolve();

    expect(state.users.page).toBe(1);
    expect(state.users.items).toEqual([currentUser]);
    expect(root!.textContent).toContain("current-user");
    expect(root!.textContent).not.toContain("stale-user");

    const block = root!.querySelector<HTMLButtonElement>(
      '[data-action="open-block-user"][data-username="current-user"]',
    );
    expect(block).not.toBeNull();
    block!.click();
    await vi.waitFor(() => {
      expect(state.dialog?.kind).toBe("block-user");
      if (state.dialog?.kind !== "block-user") return;
      expect(state.dialog.user).toEqual(currentUser);
      expect(
        root!.querySelector('dialog[data-ctx="user:current-user"]'),
      ).not.toBeNull();
    });
  });
});
