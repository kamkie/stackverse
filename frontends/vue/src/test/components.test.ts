import { afterEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, mount } from "@vue/test-utils";
import { h, nextTick } from "vue";
import type { Bookmark } from "../types";

enableAutoUnmount(afterEach);

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

    const wrapper = mount(Field, {
      props: { label: "Title", hint: "Required", error: "Title is required" },
      slots: {
        default: (slotProps) =>
          h("input", {
            id: slotProps["inputId"],
            name: "title",
            "aria-describedby": slotProps["describedBy"],
            "aria-invalid": slotProps["invalid"] || undefined,
          }),
      },
    });

    const label = wrapper.get(".sv-field");
    const input = wrapper.get<HTMLInputElement>("input[name='title']");
    const hint = wrapper.get(".sv-field-hint");
    const error = wrapper.get(".sv-field-error");

    expect(label.classes()).toContain("is-invalid");
    expect(wrapper.text()).toContain("Title");
    expect(wrapper.text()).toContain("Required");
    expect(wrapper.text()).toContain("Title is required");
    expect(input.element.id).toBeTruthy();
    expect(label.attributes("for")).toBe(input.element.id);
    expect(input.attributes("aria-invalid")).toBe("true");
    expect(input.attributes("aria-describedby")).toBe(`${hint.element.id} ${error.element.id}`);
    expect(error.attributes("role")).toBe("alert");
  });

  it("emits previous and next page requests with accessible labels", async () => {
    vi.resetModules();
    await seedMessages();
    const { default: Pagination } = await import("../components/Pagination.vue");
    const wrapper = mount(Pagination, {
      props: { page: 1, totalPages: 3 },
    });
    const buttons = wrapper.findAll("button");

    expect(wrapper.text()).toContain("2 / 3");
    expect(buttons.map((button) => button.attributes("aria-label"))).toEqual(["Previous", "Next"]);
    await buttons[0]?.trigger("click");
    await buttons[1]?.trigger("click");

    expect(wrapper.emitted("page")).toEqual([[0], [2]]);
  });

  it("omits pagination when there is only one page", async () => {
    vi.resetModules();
    await seedMessages();
    const { default: Pagination } = await import("../components/Pagination.vue");

    const wrapper = mount(Pagination, { props: { page: 0, totalPages: 1 } });

    expect(wrapper.find("nav").exists()).toBe(false);
  });

  it("renders owned bookmark actions and emits the selected bookmark", async () => {
    vi.resetModules();
    await seedMessages();
    const { default: BookmarkCard } = await import("../components/BookmarkCard.vue");
    const hiddenBookmark = { ...bookmark, status: "hidden" as const };
    const wrapper = mount(BookmarkCard, {
      props: { bookmark: hiddenBookmark, mode: "mine" },
    });
    const buttons = wrapper.findAll("button");

    expect(wrapper.attributes("data-ctx")).toBe("bookmark:00000000-0000-4000-8000-000000000123");
    expect(wrapper.text()).toContain("Hidden");
    expect(wrapper.text()).toContain("vue");
    await buttons.find((button) => button.text() === "Edit")?.trigger("click");
    await buttons.find((button) => button.text() === "Delete")?.trigger("click");

    expect(wrapper.emitted("edit")).toEqual([[hiddenBookmark]]);
    expect(wrapper.emitted("delete")).toEqual([[hiddenBookmark]]);
  });

  it("shows report actions only for authenticated feed users and disables already-reported bookmarks", async () => {
    vi.resetModules();
    await seedMessages();
    const [{ default: BookmarkCard }, auth, reportedStore] = await Promise.all([
      import("../components/BookmarkCard.vue"),
      import("../auth"),
      import("../reportedStore"),
    ]);
    const loggedOut = mount(BookmarkCard, {
      props: { bookmark, mode: "feed" },
    });
    expect(loggedOut.find("button").exists()).toBe(false);
    loggedOut.unmount();

    auth.session.value = { authenticated: true, username: "demo" };
    const active = mount(BookmarkCard, {
      props: { bookmark, mode: "feed" },
    });
    await active.get("button").trigger("click");
    expect(active.emitted("report")).toEqual([[bookmark]]);
    active.unmount();

    reportedStore.markReported(bookmark.id);
    const disabled = mount(BookmarkCard, { props: { bookmark, mode: "feed" } });
    const disabledButton = disabled.get("button");

    expect(disabledButton.text()).toBe("Reported");
    expect(disabledButton.attributes()).toHaveProperty("disabled");
  });

  it("renders toast messages and removes them after the display timeout", async () => {
    vi.resetModules();
    vi.useFakeTimers();
    const [{ default: ToastRegion }, toast] = await Promise.all([
      import("../components/ToastRegion.vue"),
      import("../toast"),
    ]);
    const wrapper = mount(ToastRegion);

    toast.showToast("Saved", "success");
    await nextTick();

    expect(wrapper.text()).toContain("Saved");
    expect(wrapper.find(".sv-toast--success").exists()).toBe(true);

    vi.advanceTimersByTime(3500);
    await nextTick();

    expect(wrapper.text()).not.toContain("Saved");
  });
});
