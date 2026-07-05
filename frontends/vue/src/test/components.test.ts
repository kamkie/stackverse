import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, h, nextTick, type Component } from "vue";
import type { Bookmark } from "../types";

function mountComponent(
  component: Component,
  props: Record<string, unknown> = {},
  slots?: Record<string, () => unknown>,
) {
  const host = document.createElement("div");
  document.body.append(host);
  const app = createApp({ render: () => h(component, props, slots) });
  app.mount(host);
  return {
    host,
    unmount: () => {
      app.unmount();
      host.remove();
    },
  };
}

const bookmark: Bookmark = {
  id: "00000000-0000-4000-8000-000000000123",
  owner: "demo",
  url: "https://example.com/vue",
  title: "Vue testing",
  notes: "Component behavior",
  tags: ["vue", "testing"],
  visibility: "public",
  status: "active",
  createdAt: "2026-01-01T00:00:00.000Z",
  updatedAt: "2026-01-01T00:00:00.000Z",
};

async function seedMessages() {
  const i18n = await import("../i18n/i18n");
  i18n.bundle.value = {
    language: "en",
    messages: {
      "ui.action.delete": "Delete",
      "ui.action.edit": "Edit",
      "ui.action.next": "Next",
      "ui.action.previous": "Previous",
      "ui.action.report": "Report",
      "ui.bookmark.hidden": "Hidden",
      "ui.report.reported": "Reported",
    },
  };
}

describe("shared components", () => {
  afterEach(() => {
    document.body.innerHTML = "";
    vi.useRealTimers();
  });

  it("renders field labels, hints, errors, and projected controls", async () => {
    vi.resetModules();
    const { default: Field } = await import("../components/Field.vue");

    const { host } = mountComponent(
      Field,
      { label: "Title", hint: "Required", error: "Title is required" },
      { default: () => h("input", { name: "title" }) },
    );

    expect(host.querySelector(".sv-field")?.classList.contains("is-invalid")).toBe(true);
    expect(host.textContent).toContain("Title");
    expect(host.textContent).toContain("Required");
    expect(host.textContent).toContain("Title is required");
    expect(host.querySelector("input[name='title']")).not.toBeNull();
  });

  it("emits previous and next page requests with accessible labels", async () => {
    vi.resetModules();
    await seedMessages();
    const { default: Pagination } = await import("../components/Pagination.vue");
    const requestedPages: number[] = [];

    const { host } = mountComponent(Pagination, {
      page: 1,
      totalPages: 3,
      onPage: (page: number) => requestedPages.push(page),
    });
    const buttons = [...host.querySelectorAll("button")];

    expect(host.textContent).toContain("2 / 3");
    expect(buttons.map((button) => button.getAttribute("aria-label"))).toEqual([
      "Previous",
      "Next",
    ]);
    buttons[0]?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    buttons[1]?.dispatchEvent(new MouseEvent("click", { bubbles: true }));

    expect(requestedPages).toEqual([0, 2]);
  });

  it("omits pagination when there is only one page", async () => {
    vi.resetModules();
    await seedMessages();
    const { default: Pagination } = await import("../components/Pagination.vue");

    const { host } = mountComponent(Pagination, { page: 0, totalPages: 1 });

    expect(host.querySelector("nav")).toBeNull();
  });

  it("renders owned bookmark actions and emits the selected bookmark", async () => {
    vi.resetModules();
    await seedMessages();
    const { default: BookmarkCard } = await import("../components/BookmarkCard.vue");
    const edited: Bookmark[] = [];
    const deleted: Bookmark[] = [];

    const { host } = mountComponent(BookmarkCard, {
      bookmark: { ...bookmark, status: "hidden" },
      mode: "mine",
      onEdit: (value: Bookmark) => edited.push(value),
      onDelete: (value: Bookmark) => deleted.push(value),
    });
    const buttons = [...host.querySelectorAll("button")];

    expect(host.innerHTML).toContain(
      'data-ctx="bookmark:00000000-0000-4000-8000-000000000123"',
    );
    expect(host.textContent).toContain("Hidden");
    expect(host.textContent).toContain("vue");
    buttons.find((button) => button.textContent?.trim() === "Edit")?.click();
    buttons.find((button) => button.textContent?.trim() === "Delete")?.click();

    expect(edited).toEqual([{ ...bookmark, status: "hidden" }]);
    expect(deleted).toEqual([{ ...bookmark, status: "hidden" }]);
  });

  it("shows report actions only for authenticated feed users and disables already-reported bookmarks", async () => {
    vi.resetModules();
    await seedMessages();
    const [{ default: BookmarkCard }, auth, reportedStore] = await Promise.all([
      import("../components/BookmarkCard.vue"),
      import("../auth"),
      import("../reportedStore"),
    ]);
    const reported: Bookmark[] = [];

    const loggedOut = mountComponent(BookmarkCard, { bookmark, mode: "feed" });
    expect(loggedOut.host.querySelector("button")).toBeNull();
    loggedOut.unmount();

    auth.session.value = { authenticated: true, username: "demo" };
    const active = mountComponent(BookmarkCard, {
      bookmark,
      mode: "feed",
      onReport: (value: Bookmark) => reported.push(value),
    });
    active.host.querySelector("button")?.click();
    expect(reported).toEqual([bookmark]);
    active.unmount();

    reportedStore.markReported(bookmark.id);
    const disabled = mountComponent(BookmarkCard, { bookmark, mode: "feed" });

    expect(disabled.host.querySelector("button")?.textContent?.trim()).toBe("Reported");
    expect(disabled.host.querySelector("button")?.hasAttribute("disabled")).toBe(true);
  });

  it("renders toast messages and removes them after the display timeout", async () => {
    vi.resetModules();
    vi.useFakeTimers();
    const [{ default: ToastRegion }, toast] = await Promise.all([
      import("../components/ToastRegion.vue"),
      import("../toast"),
    ]);
    const { host } = mountComponent(ToastRegion);

    toast.showToast("Saved", "success");
    await nextTick();

    expect(host.textContent).toContain("Saved");
    expect(host.querySelector(".sv-toast--success")).not.toBeNull();

    vi.advanceTimersByTime(3500);
    await nextTick();

    expect(host.textContent).not.toContain("Saved");
  });
});
