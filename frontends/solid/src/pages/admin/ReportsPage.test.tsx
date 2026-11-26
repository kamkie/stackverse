import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReportStatus } from "../../lib/types";
import { bookmark, jsonResponse, page, readyI18n, report } from "../../test/fixtures";
import ReportsPage from "./ReportsPage";

beforeEach(() => {
  readyI18n();
  document.cookie = "XSRF-TOKEN=moderator-token; path=/";
});

describe("ReportsPage", () => {
  it("uses the moderator endpoint for action, revision, and reopening", async () => {
    let status: ReportStatus = "open";
    const resolutions: ReportStatus[] = [];
    const fetchMock = vi.fn(async (input: URL, init?: RequestInit) => {
      if (input.pathname === "/api/v1/admin/reports/report-1" && init?.method === "PUT") {
        const resolution = (JSON.parse(init.body as string) as { resolution: ReportStatus })
          .resolution;
        resolutions.push(resolution);
        status = resolution;
        return jsonResponse(report({ status }));
      }
      if (input.pathname === "/api/v1/admin/reports") {
        return jsonResponse(page([report({ status })]));
      }
      if (input.pathname === "/api/v1/bookmarks/bookmark-1") {
        return jsonResponse(bookmark());
      }
      throw new Error(`Unexpected request: ${input}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    render(() => <ReportsPage />);

    await fireEvent.click(await screen.findByRole("button", { name: "action" }));
    await waitFor(() => {
      expect(resolutions).toEqual(["actioned"]);
      expect(
        document.querySelector('[data-ctx="report:report-1"] .sv-cell-actions .sv-badge')
          ?.textContent,
      ).toBe("actioned");
    });
    await fireEvent.click(await screen.findByRole("button", { name: "dismiss" }));
    await waitFor(() => {
      expect(resolutions).toEqual(["actioned", "dismissed"]);
      expect(
        document.querySelector('[data-ctx="report:report-1"] .sv-cell-actions .sv-badge')
          ?.textContent,
      ).toBe("dismissed");
    });
    await fireEvent.click(await screen.findByRole("button", { name: "action" }));
    await waitFor(() => {
      expect(resolutions).toEqual(["actioned", "dismissed", "actioned"]);
      expect(
        document.querySelector('[data-ctx="report:report-1"] .sv-cell-actions .sv-badge')
          ?.textContent,
      ).toBe("actioned");
    });
    await fireEvent.click(await screen.findByRole("button", { name: "reopen" }));
    await waitFor(() => {
      expect(resolutions).toEqual(["actioned", "dismissed", "actioned", "open"]);
      expect(
        document.querySelector('[data-ctx="report:report-1"] .sv-cell-actions .sv-badge'),
      ).toBeNull();
    });

    const mutationCalls = fetchMock.mock.calls.filter(
      ([, init]) => (init as RequestInit | undefined)?.method === "PUT",
    );
    expect(mutationCalls).toHaveLength(4);
    for (const [url, init] of mutationCalls as [URL, RequestInit][]) {
      expect(url.pathname).toBe("/api/v1/admin/reports/report-1");
      expect((init.headers as Headers).get("X-XSRF-TOKEN")).toBe("moderator-token");
    }
  });

  it("requests the selected page and resets it when the status changes", async () => {
    const fetchMock = vi.fn(async (url: URL) => {
      if (url.pathname === "/api/v1/bookmarks/bookmark-1") return jsonResponse(bookmark());
      if (url.pathname !== "/api/v1/admin/reports") {
        throw new Error(`Unexpected request: ${url}`);
      }
      return jsonResponse(page([report()], { totalPages: 2 }));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(() => <ReportsPage />);

    await fireEvent.click(await screen.findByRole("button", { name: "next" }));
    expect(await screen.findByText("2 / 2")).toBeTruthy();
    await fireEvent.change(screen.getByRole("combobox"), { target: { value: "dismissed" } });
    expect(await screen.findByText("1 / 2")).toBeTruthy();

    const requests = fetchMock.mock.calls
      .map(([url]) => url)
      .filter((url) => url.pathname === "/api/v1/admin/reports");
    expect(requests.map(({ searchParams }) => Object.fromEntries(searchParams))).toEqual([
      { status: "open", page: "0" },
      { status: "open", page: "1" },
      { status: "dismissed", page: "0" },
    ]);
  });

  it("surfaces authorization failures from the privileged list endpoint", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(
          { title: "Forbidden", detail: "Moderator role required", status: 403 },
          { status: 403 },
        ),
      ),
    );
    render(() => <ReportsPage />);

    expect((await screen.findByRole("alert")).textContent).toContain(
      "Moderator role required",
    );
  });
});
