import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/svelte";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import BookmarkCard from "../components/BookmarkCard.svelte";
import BookmarkContext from "../components/BookmarkContext.svelte";
import ConfirmDialog from "../components/ConfirmDialog.svelte";
import TagSidebar from "../components/TagSidebar.svelte";
import ToastRegion from "../components/ToastRegion.svelte";
import {
  bookmark,
  installDialogPolyfill,
  seedMessages,
  stubFetch,
} from "./test-helpers";

beforeEach(() => {
  seedMessages();
  installDialogPolyfill();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("shared Svelte components", () => {
  it("renders owned hidden bookmarks and passes the selected entity to actions", async () => {
    const item = bookmark({ status: "hidden" });
    const onEdit = vi.fn();
    const onDelete = vi.fn();
    const { container } = render(BookmarkCard, {
      bookmark: item,
      mode: "own",
      onEdit,
      onDelete,
    });

    expect(
      container.querySelector(`[data-ctx="bookmark:${item.id}"]`),
    ).not.toBeNull();
    expect(screen.getByText("Hidden")).toBeTruthy();
    expect(screen.getByText("svelte")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    await fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(onEdit).toHaveBeenCalledExactlyOnceWith(item);
    expect(onDelete).toHaveBeenCalledExactlyOnceWith(item);
  });

  it("shows report actions only when supplied and pins already-reported state", async () => {
    const item = bookmark({ id: "00000000-0000-4000-8000-000000000111" });
    const onReport = vi.fn();
    const active = render(BookmarkCard, {
      bookmark: item,
      mode: "feed",
      onReport,
    });
    await fireEvent.click(screen.getByRole("button", { name: "Report" }));
    expect(onReport).toHaveBeenCalledExactlyOnceWith(item);
    active.unmount();

    render(BookmarkCard, {
      bookmark: item,
      mode: "feed",
      reported: true,
      onReport,
    });
    expect(
      screen.getByRole<HTMLButtonElement>("button", { name: "Reported" })
        .disabled,
    ).toBe(true);
  });

  it("loads tag counts and toggles the selected tag", async () => {
    const requests = stubFetch(() =>
      Response.json({ tags: [{ tag: "svelte", count: 3 }] }),
    );
    const onSelect = vi.fn();
    render(TagSidebar, { selected: "svelte", onSelect });

    const tag = await screen.findByRole("button", { name: "svelte 3" });
    expect(tag.classList.contains("is-active")).toBe(true);
    await fireEvent.click(tag);
    expect(onSelect).toHaveBeenCalledExactlyOnceWith("");
    expect(new URL(requests[0]?.url ?? location.origin).pathname).toBe(
      "/api/v1/tags",
    );
  });

  it("renders bookmark context and masks lookup failures with the id", async () => {
    const available = bookmark({ title: "Context title" });
    stubFetch((request) =>
      new URL(request.url).pathname.endsWith(available.id)
        ? Response.json(available)
        : new Response(null, { status: 404 }),
    );
    const loaded = render(BookmarkContext, { bookmarkId: available.id });
    expect(await screen.findByText("Context title")).toBeTruthy();
    loaded.unmount();
    render(BookmarkContext, { bookmarkId: "missing-bookmark" });
    expect(screen.getByText("missing-bookmark")).toBeTruthy();
    expect(await screen.findByText("Bookmark unavailable")).toBeTruthy();
  });

  it("submits confirmations without closing away the entity context", async () => {
    const onConfirm = vi.fn();
    const onClose = vi.fn();
    const { container } = render(ConfirmDialog, {
      title: "Delete - Svelte guide",
      body: "Delete this bookmark?",
      confirmLabel: "Delete",
      cancelLabel: "Cancel",
      ctx: "bookmark:101",
      onConfirm,
      onClose,
    });
    const dialog = container.querySelector<HTMLDialogElement>("dialog");
    expect(dialog?.open).toBe(true);
    expect(dialog?.dataset.ctx).toBe("bookmark:101");
    await fireEvent.submit(container.querySelector("form") as HTMLFormElement);
    expect(onConfirm).toHaveBeenCalledOnce();
    await fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("renders success and danger toasts in the polite live region", async () => {
    render(ToastRegion, {
      toasts: [
        { id: 1, message: "Saved", tone: "success" },
        { id: 2, message: "Failed", tone: "danger" },
      ],
    });
    const region = screen.getByRole("status");
    expect(region.getAttribute("aria-live")).toBe("polite");
    await waitFor(() => {
      expect(region.textContent).toContain("Saved");
      expect(region.textContent).toContain("Failed");
    });
  });
});
