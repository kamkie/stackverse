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
