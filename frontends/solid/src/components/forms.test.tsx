import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { isReported, removeReported } from "../lib/reportedStore";
import { bookmark, jsonResponse, readyI18n } from "../test/fixtures";
import BookmarkFormDialog from "./BookmarkFormDialog";
import ReportDialog from "./ReportDialog";

beforeEach(() => {
  readyI18n();
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  removeReported("bookmark-1");
});

describe("BookmarkFormDialog", () => {
  it("creates a bookmark with the contract payload and omits an empty optional note", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(bookmark()));
    vi.stubGlobal("fetch", fetchMock);
    const onSaved = vi.fn();
    const onClose = vi.fn();
    const { container } = render(() => (
      <BookmarkFormDialog onSaved={onSaved} onClose={onClose} />
    ));

    await fireEvent.input(screen.getByLabelText("url"), {
      target: { value: "https://solidjs.com" },
    });
    await fireEvent.input(screen.getByLabelText("title"), {
      target: { value: "Solid docs" },
    });
    await fireEvent.input(screen.getByLabelText(/^tags/), {
      target: { value: "solid, typescript  signals" },
    });
    await fireEvent.change(screen.getByLabelText("visibility"), {
      target: { value: "public" },
    });
    await fireEvent.submit(container.querySelector("form")!);

    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
    expect(onClose).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(url.pathname).toBe("/api/v1/bookmarks");
    expect(init.method).toBe("POST");
    expect((init.headers as Headers).get("X-XSRF-TOKEN")).toBe("test-token");
    expect(JSON.parse(init.body as string)).toEqual({
      url: "https://solidjs.com",
      title: "Solid docs",
      tags: ["solid", "typescript", "signals"],
      visibility: "public",
    });
  });

  it("maps localized validation errors to their fields without closing", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          title: "Validation failed",
          status: 400,
          errors: [
            {
              field: "title",
              messageKey: "validation.title.required",
              message: "A title is required",
            },
            {
              field: "tags",
              messageKey: "validation.tags.invalid",
              message: "Tags must be lowercase slugs",
            },
          ],
        },
        { status: 400 },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);
    const onClose = vi.fn();
    const { container } = render(() => (
      <BookmarkFormDialog onSaved={vi.fn()} onClose={onClose} />
    ));

    await fireEvent.submit(container.querySelector("form")!);

    expect(await screen.findByText("A title is required")).toBeTruthy();
    expect(screen.getByText("Tags must be lowercase slugs")).toBeTruthy();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("uses the owner-only update endpoint and surfaces hidden-public conflicts", async () => {
    const existing = bookmark({ id: "hidden-1", status: "hidden" });
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ title: "Conflict", detail: "Hidden bookmark" }, { status: 409 }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const { container } = render(() => (
      <BookmarkFormDialog bookmark={existing} onSaved={vi.fn()} onClose={vi.fn()} />
    ));

    await fireEvent.submit(container.querySelector("form")!);

    expect((await screen.findByRole("alert")).textContent).toContain("hidden-publish");
    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(url.pathname).toBe("/api/v1/bookmarks/hidden-1");
    expect(init.method).toBe("PUT");
  });
});

describe("ReportDialog", () => {
  it("submits a public-bookmark report, records the local marker, and refreshes its caller", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ id: "report-1", bookmarkId: "bookmark-1", status: "open" }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const toast = vi.fn();
    const onDone = vi.fn();
    const onClose = vi.fn();
    const { container } = render(() => (
      <ReportDialog
        bookmark={bookmark()}
        toast={toast}
        onDone={onDone}
        onClose={onClose}
      />
    ));

    await fireEvent.change(screen.getByLabelText("reason"), {
      target: { value: "broken-link" },
    });
    await fireEvent.input(screen.getByLabelText("comment"), {
      target: { value: "The target is gone" },
    });
    await fireEvent.submit(container.querySelector("form")!);

    await waitFor(() => expect(onDone).toHaveBeenCalledOnce());
    expect(isReported("bookmark-1")).toBe(true);
    expect(toast).toHaveBeenCalledWith("report-submitted");
    expect(onClose).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(url.pathname).toBe("/api/v1/bookmarks/bookmark-1/reports");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({
      reason: "broken-link",
      comment: "The target is gone",
    });
  });

  it("treats the duplicate-open-report conflict as an already-recorded report", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse({ title: "Conflict", detail: "Already reported" }, { status: 409 }),
      ),
    );
    const toast = vi.fn();
    const onDone = vi.fn();
    const onClose = vi.fn();
    const { container } = render(() => (
      <ReportDialog
        bookmark={bookmark()}
        toast={toast}
        onDone={onDone}
        onClose={onClose}
      />
    ));

    await fireEvent.submit(container.querySelector("form")!);

    await waitFor(() => expect(onDone).toHaveBeenCalledOnce());
    expect(isReported("bookmark-1")).toBe(true);
    expect(toast).toHaveBeenCalledWith("report-duplicate");
    expect(onClose).toHaveBeenCalledOnce();
  });
});
