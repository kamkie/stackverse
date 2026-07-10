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
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
    document.body.replaceChildren();
    history.replaceState(null, "", "/");
    localStorage.clear();
    sessionStorage.clear();
  });

  it("keeps a replacement report dialog intact for every completion path", async () => {
    history.replaceState(null, "", "/feed");
    const reportResponses: Array<ReturnType<typeof deferred<Response>>> = [];
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input, init) => {
        const path = new URL(String(input), window.location.origin).pathname;
        if (path === "/api/v1/messages/bundle") {
          return jsonResponse({
            language: "en",
            messages: {
              "ui.toast.report-submitted": "Report submitted",
              "ui.toast.report-duplicate": "Report already submitted",
            },
          });
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
          const response = reportResponses.shift();
          if (!response) throw new Error("Unexpected report request");
          return response.promise;
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

    const openDialog = async (): Promise<HTMLFormElement> => {
      openReport.click();
      return vi.waitFor(() => {
        const form = document.querySelector<HTMLFormElement>(
          'form[data-form="report-bookmark"]',
        );
        expect(form).not.toBeNull();
        return form!;
      });
    };

    const closeDialog = async (): Promise<void> => {
      const close = document.querySelector<HTMLButtonElement>(
        '[data-action="close-dialog"]',
      );
      expect(close).not.toBeNull();
      close!.click();
      await vi.waitFor(() => {
        expect(document.querySelector("dialog.sv-dialog")).toBeNull();
      });
    };

    vi.useFakeTimers();
    const cases = [
      {
        name: "success",
        response: () => jsonResponse({ id: "report-1" }, 201),
        toast: "Report submitted",
        reported: true,
      },
      {
        name: "duplicate",
        response: () =>
          jsonResponse({ title: "Already reported", status: 409 }, 409),
        toast: "Report already submitted",
        reported: true,
      },
      {
        name: "generic error",
        response: () =>
          jsonResponse({ title: "Unavailable", status: 500 }, 500),
        toast: null,
        reported: false,
      },
    ];

    for (const testCase of cases) {
      sessionStorage.removeItem(REPORTED_STORAGE_KEY);
      const pendingResponse = deferred<Response>();
      reportResponses.push(pendingResponse);
      const requestCount = fetchMock.mock.calls.filter(
        ([input, init]) =>
          new URL(String(input), window.location.origin).pathname ===
            "/api/v1/bookmarks/bookmark-1/reports" && init?.method === "POST",
      ).length;

      const originalForm = await openDialog();
      originalForm.dispatchEvent(
        new SubmitEvent("submit", { bubbles: true, cancelable: true }),
      );
      await vi.waitFor(() => {
        const nextRequestCount = fetchMock.mock.calls.filter(
          ([input, init]) =>
            new URL(String(input), window.location.origin).pathname ===
              "/api/v1/bookmarks/bookmark-1/reports" && init?.method === "POST",
        ).length;
        expect(nextRequestCount).toBe(requestCount + 1);
      });

      await closeDialog();
      const replacementForm = await openDialog();
      const replacementComment = replacementForm.elements.namedItem("comment");
      if (!(replacementComment instanceof HTMLTextAreaElement)) {
        throw new Error("Replacement report comment was not rendered");
      }
      const expectedComment = `replacement after ${testCase.name}`;
      replacementComment.value = expectedComment;
      replacementComment.dispatchEvent(new Event("input", { bubbles: true }));
      const replacementDialog = state.dialog;
      expect(replacementDialog?.kind).toBe("report-bookmark");
      if (replacementDialog?.kind !== "report-bookmark") {
        throw new Error("Replacement report dialog was not retained");
      }
      expect(replacementDialog.values?.comment).toBe(expectedComment);
      expect(replacementDialog.error).toBeUndefined();

      const completionRenderVersion = state.renderVersion;
      pendingResponse.resolve(testCase.response());

      await vi.waitFor(() => {
        expect(state.renderVersion).toBeGreaterThan(completionRenderVersion);
        expect(state.dialog).toBe(replacementDialog);
        expect(replacementDialog.values?.comment).toBe(expectedComment);
        expect(
          document.querySelector<HTMLTextAreaElement>(
            'textarea[name="comment"]',
          )?.value,
        ).toBe(expectedComment);
      });
      expect(replacementDialog.error).toBeUndefined();

      const reported = JSON.parse(
        sessionStorage.getItem(REPORTED_STORAGE_KEY) ?? "[]",
      ) as string[];
      expect(reported.includes("bookmark-1")).toBe(testCase.reported);
      if (testCase.toast) {
        const toast = document.querySelector<HTMLElement>(".sv-toast");
        expect(toast?.textContent).toBe(testCase.toast);
        expect(state.toasts).toHaveLength(1);
        await vi.advanceTimersByTimeAsync(5000);
        await vi.waitFor(() => {
          expect(state.toasts).toHaveLength(0);
          expect(document.querySelector(".sv-toast")).toBeNull();
        });
        expect(vi.getTimerCount()).toBe(0);
      } else {
        expect(state.toasts).toHaveLength(0);
        expect(document.querySelector(".sv-toast")).toBeNull();
      }
      await closeDialog();
    }

    expect(reportResponses).toHaveLength(0);
  });
});
