import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/svelte";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { isReported, markReported, removeReported } from "../lib/reportedStore";
import type { ReportStatus } from "../lib/types";
import MyReportsPage from "../pages/MyReportsPage.svelte";
import ReportsPage from "../pages/admin/ReportsPage.svelte";
import {
  bookmark,
  installDialogPolyfill,
  page,
  problem,
  report,
  seedMessages,
  stubFetch,
  timestamp,
} from "./test-helpers";

beforeEach(() => {
  seedMessages();
  installDialogPolyfill();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("reporter self-service", () => {
  it("shows bookmark context, preserves validation errors, edits, and withdraws", async () => {
    let open = report();
    const resolved = report({
      id: "00000000-0000-4000-8000-000000000302",
      bookmarkId: "00000000-0000-4000-8000-000000000102",
      status: "dismissed",
      resolutionNote: "No violation",
    });
    let openExists = true;
    let rejectEdit = true;
    let updateBody: unknown;
    const requests = stubFetch(async (request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/api/v1/reports") {
        return Response.json(page(openExists ? [open, resolved] : [resolved]));
      }
      if (
        request.method === "GET" &&
        url.pathname === `/api/v1/bookmarks/${open.bookmarkId}`
      ) {
        return Response.json(bookmark({ title: "Reported bookmark" }));
      }
      if (
        request.method === "GET" &&
        url.pathname === `/api/v1/bookmarks/${resolved.bookmarkId}`
      ) {
        return problem(404, "Bookmark hidden");
      }
      if (
        request.method === "PUT" &&
        url.pathname === `/api/v1/reports/${open.id}`
      ) {
        if (rejectEdit) {
          return problem(400, "Invalid report", [
            {
              field: "comment",
              messageKey: "validation.comment.length",
              message: "Localized comment error",
            },
          ]);
        }
        updateBody = await request.clone().json();
        open = { ...open, reason: "other", comment: "Updated context" };
        return Response.json(open);
      }
      if (
        request.method === "DELETE" &&
        url.pathname === `/api/v1/reports/${open.id}`
      ) {
        openExists = false;
        return new Response(null, { status: 204 });
      }
      return new Response(null, { status: 404 });
    });
    const toast = vi.fn();
    markReported(open.bookmarkId);
    render(MyReportsPage, { toast });
    expect(await screen.findByText("Reported bookmark")).toBeTruthy();
    expect(await screen.findByText("Bookmark unavailable")).toBeTruthy();

    await fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    let dialog = await screen.findByRole("dialog");
    await fireEvent.submit(dialog.querySelector("form") as HTMLFormElement);
    expect(
      await within(dialog).findByText("Localized comment error"),
    ).toBeTruthy();

    rejectEdit = false;
    dialog = screen.getByRole("dialog");
    await fireEvent.change(within(dialog).getByLabelText("Reason"), {
      target: { value: "other" },
    });
    await fireEvent.input(within(dialog).getByLabelText(/Comment/), {
      target: { value: "Updated context" },
    });
    await fireEvent.submit(dialog.querySelector("form") as HTMLFormElement);
    expect(await screen.findByText("Updated context")).toBeTruthy();
    expect(updateBody).toEqual({ reason: "other", comment: "Updated context" });
    expect(toast).toHaveBeenCalledWith("Report updated");
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    await fireEvent.click(screen.getByRole("button", { name: "Withdraw" }));
    dialog = await screen.findByRole("dialog");
    await fireEvent.submit(dialog.querySelector("form") as HTMLFormElement);
    await waitFor(() =>
      expect(screen.queryByText("Updated context")).toBeNull(),
    );
    expect(isReported(open.bookmarkId)).toBe(false);
    expect(toast).toHaveBeenCalledWith("Report withdrawn");
    expect(requests.some((request) => request.method === "DELETE")).toBe(true);
    removeReported(open.bookmarkId);
  });
});

describe("moderation queue", () => {
  it("dismisses and reopens a report through the privileged endpoint", async () => {
    let current = report({ reporter: "alice" });
    const resolutionBodies: unknown[] = [];
    const requests = stubFetch(async (request) => {
      const url = new URL(request.url);
      if (
        request.method === "GET" &&
        url.pathname === "/api/v1/admin/reports"
      ) {
        const requested = url.searchParams.get("status") as ReportStatus;
        return Response.json(
          page(current.status === requested ? [current] : []),
        );
      }
      if (request.method === "GET" && url.pathname.includes("/bookmarks/")) {
        return Response.json(bookmark({ title: "Moderated bookmark" }));
      }
      if (
        request.method === "PUT" &&
        url.pathname === `/api/v1/admin/reports/${current.id}`
      ) {
        const body = (await request.clone().json()) as {
          resolution: ReportStatus;
        };
        resolutionBodies.push(body);
        current = {
          ...current,
          status: body.resolution,
          ...(body.resolution === "open"
            ? { resolvedBy: undefined, resolvedAt: undefined }
            : { resolvedBy: "moderator", resolvedAt: timestamp }),
        };
        return Response.json(current);
      }
      return new Response(null, { status: 404 });
    });

    const { container } = render(ReportsPage);
    expect(await screen.findByText("Moderated bookmark")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));
    expect(await screen.findByText("No reports to review")).toBeTruthy();
    await fireEvent.change(
      container.querySelector(".sv-toolbar select") as HTMLSelectElement,
      {
        target: { value: "dismissed" },
      },
    );
    expect(await screen.findByText("Moderated bookmark")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Action" })).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Reopen" }));
    await waitFor(() => {
      expect(resolutionBodies).toEqual([
        { resolution: "dismissed" },
        { resolution: "open" },
      ]);
    });
    expect(
      requests
        .filter((request) => request.method === "PUT")
        .every(
          (request) =>
            new URL(request.url).pathname ===
            `/api/v1/admin/reports/${current.id}`,
        ),
    ).toBe(true);
  });

  it("renders a moderation failure instead of stale queue data", async () => {
    const open = report();
    stubFetch((request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname.includes("/bookmarks/")) {
        return Response.json(bookmark({ title: "Moderated bookmark" }));
      }
      if (
        request.method === "GET" &&
        url.pathname === "/api/v1/admin/reports"
      ) {
        return Response.json(page([open]));
      }
      if (request.method === "PUT")
        return problem(503, "Moderation unavailable");
      return new Response(null, { status: 404 });
    });
    render(ReportsPage);
    expect(await screen.findByText("Moderated bookmark")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Action" }));
    expect(await screen.findByRole("alert")).toHaveProperty(
      "textContent",
      "Moderation unavailable",
    );
    expect(screen.queryByText("Moderated bookmark")).toBeNull();
  });
});
