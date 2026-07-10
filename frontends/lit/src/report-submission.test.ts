import { APP_ACTIONS } from "./app-actions";
import { REPORTED_STORAGE_KEY, state } from "./app-state";

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

describe("report submission", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    document.body.replaceChildren();
    history.replaceState(null, "", "/");
    localStorage.clear();
    sessionStorage.clear();
  });

  it("finishes with captured bookmark context after the dialog closes", async () => {
    history.replaceState(null, "", "/feed");
    const reportResponse = deferred<Response>();
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input, init) => {
        const path = new URL(String(input), window.location.origin).pathname;
        if (path === "/api/v1/messages/bundle") {
          return jsonResponse({ language: "en", messages: {} });
        }
        if (path === "/auth/session") {
          return jsonResponse({ authenticated: false });
        }
        if (path === "/api/v2/bookmarks") {
          return jsonResponse({ items: [] });
        }
        if (
          path === "/api/v1/bookmarks/bookmark-1/reports" &&
          init?.method === "POST"
        ) {
          return reportResponse.promise;
        }
        return new Response(null, { status: 404 });
      });

    document.body.innerHTML = "<stackverse-app></stackverse-app>";
    await import("./main");
    await vi.waitFor(() => {
      expect(document.querySelector(".sv-app")).not.toBeNull();
    });

    state.feed.pages = [
      {
        items: [
          {
            id: "bookmark-1",
            owner: "demo",
            url: "https://example.com",
            title: "Example",
            tags: [],
            visibility: "public",
            status: "active",
            createdAt: "2026-07-10T00:00:00Z",
            updatedAt: "2026-07-10T00:00:00Z",
          },
        ],
      },
    ];
    const openReport = document.createElement("button");
    openReport.dataset.action = APP_ACTIONS.openReport;
    openReport.dataset.id = "bookmark-1";
    document.body.append(openReport);
    openReport.click();

    const form = await vi.waitFor(() => {
      const element = document.querySelector<HTMLFormElement>(
        'form[data-form="report-bookmark"]',
      );
      expect(element).not.toBeNull();
      return element!;
    });
    form.dispatchEvent(
      new SubmitEvent("submit", { bubbles: true, cancelable: true }),
    );
    await vi.waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) =>
            new URL(String(input), window.location.origin).pathname ===
              "/api/v1/bookmarks/bookmark-1/reports" && init?.method === "POST",
        ),
      ).toBe(true);
    });

    const close = document.querySelector<HTMLButtonElement>(
      '[data-action="close-dialog"]',
    );
    expect(close).not.toBeNull();
    close!.click();
    await vi.waitFor(() => {
      expect(document.querySelector("dialog.sv-dialog")).toBeNull();
    });

    reportResponse.resolve(jsonResponse({ id: "report-1" }, 201));

    await vi.waitFor(() => {
      const reported = JSON.parse(
        sessionStorage.getItem(REPORTED_STORAGE_KEY) ?? "[]",
      ) as string[];
      expect(reported).toContain("bookmark-1");
      expect(state.dialog).toBeNull();
      expect(state.toasts).toHaveLength(1);
    });
  });
});
