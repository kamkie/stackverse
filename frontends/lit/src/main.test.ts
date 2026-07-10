import { fixture } from "@open-wc/testing-helpers";
import { html } from "lit";
import { APP_ACTIONS } from "./app-actions";
import { state } from "./app-state";

interface TestAppElement extends HTMLElement {
  requestUpdate(): void;
  updateComplete: Promise<boolean>;
}

describe("stackverse-app custom element", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  it("renders light DOM and preserves a dialog when a toast expires", async () => {
    vi.spyOn(console, "debug").mockImplementation(() => undefined);
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input, init) => {
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
        if (
          url.pathname === "/api/v1/bookmarks/bookmark-1/reports" &&
          init?.method === "POST"
        ) {
          return new Response(
            JSON.stringify({
              title: "Conflict",
              status: 409,
              detail: "Report already exists",
            }),
            {
              status: 409,
              headers: { "Content-Type": "application/problem+json" },
            },
          );
        }
        return new Response(null, { status: 404 });
      });

    document.body.innerHTML = "<stackverse-app></stackverse-app>";
    await import("./main");
    const host = document.querySelector<TestAppElement>("stackverse-app");
    expect(host?.shadowRoot).toBeNull();
    await vi.waitFor(() => {
      expect(host?.querySelector(".sv-app")).not.toBeNull();
      expect(host?.textContent).toContain("Public feed");
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
    host!.append(openReport);
    openReport.click();
    await vi.waitFor(() => {
      expect(host?.querySelector("dialog.sv-dialog")).not.toBeNull();
    });

    vi.useFakeTimers();
    const reportForm = host!.querySelector<HTMLFormElement>(
      'form[data-form="report-bookmark"]',
    );
    expect(reportForm).not.toBeNull();
    reportForm!.dispatchEvent(
      new SubmitEvent("submit", { bubbles: true, cancelable: true }),
    );
    await vi.waitFor(() => expect(state.toasts).toHaveLength(1));

    const openDialog = document.createElement("button");
    openDialog.dataset.action = APP_ACTIONS.openBookmarkCreate;
    host!.append(openDialog);
    openDialog.click();
    await vi.waitFor(() => {
      expect(host?.querySelector('form[data-form="bookmark"]')).not.toBeNull();
    });

    const dialogBefore =
      host!.querySelector<HTMLDialogElement>("dialog.sv-dialog");
    const urlInput =
      dialogBefore?.querySelector<HTMLInputElement>('input[name="url"]');
    expect(urlInput).not.toBeNull();
    urlInput!.focus();
    urlInput!.value = "https://example.com";
    urlInput!.setSelectionRange(8, 8);
    urlInput!.dispatchEvent(new InputEvent("input", { bubbles: true }));

    await vi.advanceTimersByTimeAsync(5000);
    await host!.updateComplete;

    const dialogAfter =
      host!.querySelector<HTMLDialogElement>("dialog.sv-dialog");
    expect(dialogAfter).toBe(dialogBefore);
    expect(document.activeElement).toBe(urlInput);
    expect(urlInput?.selectionStart).toBe(8);
    expect(urlInput?.value).toBe("https://example.com");
    expect(state.toasts).toEqual([]);

    await fixture<HTMLElement>(html`<stackverse-app></stackverse-app>`);

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
