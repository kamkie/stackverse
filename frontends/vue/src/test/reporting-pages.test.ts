import { enableAutoUnmount, mount } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import type { Bookmark, Report, ReportStatus } from "../types";
import { page, problem, seedMessages, settle, stubFetch } from "./page-test-helpers";

vi.mock("vue-router", () => ({
  RouterLink: { template: "<a><slot /></a>" },
  RouterView: { template: "<div />" },
}));

enableAutoUnmount(afterEach);

const timestamp = "2026-07-11T12:00:00.000Z";

function bookmark(id: string, title: string): Bookmark {
  return {
    id,
    owner: "alice",
    url: "https://example.com/reported",
    title,
    notes: "Reported content",
    tags: ["vue"],
    visibility: "public",
    status: "active",
    createdAt: timestamp,
    updatedAt: timestamp,
  };
}

function report(overrides: Partial<Report> = {}): Report {
  return {
    id: "00000000-0000-4000-8000-000000000301",
    bookmarkId: "00000000-0000-4000-8000-000000000101",
    reporter: "demo",
    reason: "spam",
    comment: "Suspicious content",
    status: "open",
    createdAt: timestamp,
    ...overrides,
  };
}

async function prepareMessages(): Promise<void> {
  await seedMessages({
    "ui.action.action": "Action",
    "ui.action.dismiss": "Dismiss",
    "ui.action.edit": "Edit",
    "ui.action.reopen": "Reopen",
    "ui.action.save": "Save",
    "ui.action.withdraw": "Withdraw",
    "ui.admin.audit": "Audit",
    "ui.admin.dashboard": "Dashboard",
    "ui.admin.messages": "Messages",
    "ui.admin.reports": "Reports",
    "ui.admin.users": "Users",
    "ui.nav.admin": "Admin",
    "ui.reports.bookmark-unavailable": "Bookmark unavailable",
    "ui.reports.empty": "No reports to review",
    "ui.toast.report-updated": "Report updated",
    "ui.toast.report-withdrawn": "Report withdrawn",
  });
}

describe("my reports", () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("loads bookmark context, edits an open report, and withdraws it", async () => {
    const open = report();
    const resolved = report({
      id: "00000000-0000-4000-8000-000000000302",
      bookmarkId: "00000000-0000-4000-8000-000000000102",
      status: "dismissed",
      resolutionNote: "No violation",
    });
    const requests = stubFetch(async (request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/api/v1/reports") {
        return Response.json(page([open, resolved]));
      }
      if (request.method === "GET" && url.pathname.endsWith(open.bookmarkId)) {
        return Response.json(bookmark(open.bookmarkId, "Reported bookmark"));
      }
      if (request.method === "GET" && url.pathname.endsWith(resolved.bookmarkId)) {
        return problem(404, "Bookmark hidden");
      }
      if (request.method === "PUT" && url.pathname === `/api/v1/reports/${open.id}`) {
        return Response.json({
          ...open,
          reason: "other",
          comment: "Updated context",
        });
      }
      if (request.method === "DELETE") return new Response(null, { status: 204 });
      return new Response(null, { status: 404 });
    });
    await prepareMessages();
    const reportedStore = await import("../reportedStore");
    reportedStore.markReported(open.bookmarkId);
    const { default: MyReportsPage } = await import("../pages/MyReportsPage.vue");
    const wrapper = mount(MyReportsPage);
    await settle();

    expect(wrapper.text()).toContain("Reported bookmark");
    expect(wrapper.text()).toContain("Bookmark unavailable");
    expect(wrapper.findAll("button").filter((button) => button.text() === "Edit")).toHaveLength(1);

    await wrapper
      .findAll("button")
      .find((button) => button.text() === "Edit")
      ?.trigger("click");
    let dialog = wrapper.get("dialog");
    await dialog.get("select").setValue("other");
    await dialog.get("textarea").setValue("Updated context");
    await dialog.get("form").trigger("submit");
    await settle();

    const update = requests.find((request) => request.method === "PUT");
    expect(await update?.json()).toEqual({ reason: "other", comment: "Updated context" });
    expect(wrapper.text()).toContain("Updated context");
    expect((await import("../toast")).toasts.value.at(-1)?.message).toBe("Report updated");

    await wrapper
      .findAll("button")
      .find((button) => button.text() === "Withdraw")
      ?.trigger("click");
    dialog = wrapper.get("dialog");
    await dialog.get("form").trigger("submit");
    await settle();

    expect(wrapper.text()).not.toContain("Updated context");
    expect(requests.some((request) => request.method === "DELETE")).toBe(true);
    expect(reportedStore.isReported(open.bookmarkId)).toBe(false);
    expect((await import("../toast")).toasts.value.at(-1)?.message).toBe("Report withdrawn");
  });

  it("keeps an edit open for localized field errors and exposes list failures", async () => {
    const open = report();
    let listFails = false;
    stubFetch((request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/api/v1/reports") {
        return listFails ? problem(503, "Reports unavailable") : Response.json(page([open]));
      }
      if (request.method === "GET" && url.pathname.includes("/bookmarks/")) {
        return Response.json(bookmark(open.bookmarkId, "Reported bookmark"));
      }
      if (request.method === "PUT" && url.pathname === `/api/v1/reports/${open.id}`) {
        return problem(400, "Invalid report", [
          {
            field: "comment",
            messageKey: "validation.length",
            message: "Localized comment error",
          },
        ]);
      }
      return new Response(null, { status: 404 });
    });
    await prepareMessages();
    const { default: MyReportsPage } = await import("../pages/MyReportsPage.vue");
    const wrapper = mount(MyReportsPage);
    await settle();

    await wrapper
      .findAll("button")
      .find((button) => button.text() === "Edit")
      ?.trigger("click");
    await wrapper.get("dialog form").trigger("submit");
    await settle();

    expect(wrapper.get(".sv-field-error").text()).toBe("Localized comment error");
    expect(wrapper.find("dialog").exists()).toBe(true);

    listFails = true;
    await wrapper.get(".sv-toolbar select").setValue("dismissed");
    await settle();
    expect(wrapper.get("[role='alert']").text()).toBe("Reports unavailable");
  });
});

describe("moderation reports", () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("dismisses and reopens reports across queue filters", async () => {
    let current = report({ reporter: "alice" });
    const requests = stubFetch(async (request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/api/v1/admin/reports") {
        const requestedStatus = url.searchParams.get("status") as ReportStatus;
        return Response.json(page(current.status === requestedStatus ? [current] : []));
      }
      if (request.method === "GET" && url.pathname.includes("/bookmarks/")) {
        return Response.json(bookmark(current.bookmarkId, "Moderated bookmark"));
      }
      if (request.method === "PUT" && url.pathname === `/api/v1/admin/reports/${current.id}`) {
        const body = (await request.json()) as { resolution: ReportStatus };
        const next: Report = { ...current, status: body.resolution };
        if (body.resolution === "open") {
          delete next.resolvedBy;
          delete next.resolvedAt;
        } else {
          next.resolvedBy = "moderator";
          next.resolvedAt = timestamp;
        }
        current = next;
        return Response.json(current);
      }
      return new Response(null, { status: 404 });
    });
    await prepareMessages();
    const { default: ReportsPage } = await import("../pages/admin/ReportsPage.vue");
    const wrapper = mount(ReportsPage);
    await settle();

    expect(wrapper.text()).toContain("Moderated bookmark");
    expect(wrapper.text()).toContain("alice");
    await wrapper
      .findAll("button")
      .find((button) => button.text() === "Dismiss")
      ?.trigger("click");
    await settle();
    expect(wrapper.get(".sv-empty").text()).toBe("No reports to review");
    expect(wrapper.text()).not.toContain("Moderated bookmark");

    await wrapper.get("select").setValue("dismissed");
    await settle();
    expect(wrapper.text()).toContain("Moderated bookmark");
    expect(wrapper.findAll("button").map((button) => button.text())).toEqual(["Action", "Reopen"]);

    await wrapper
      .findAll("button")
      .find((button) => button.text() === "Reopen")
      ?.trigger("click");
    await settle();

    const resolutionRequests = requests.filter((request) => request.method === "PUT");
    expect(resolutionRequests.map((request) => new URL(request.url).pathname)).toEqual([
      `/api/v1/admin/reports/${current.id}`,
      `/api/v1/admin/reports/${current.id}`,
    ]);
    const resolutions = await Promise.all(resolutionRequests.map((request) => request.json()));
    expect(resolutions).toEqual([{ resolution: "dismissed" }, { resolution: "open" }]);
  });

  it("renders resolution failures without losing the queue", async () => {
    const open = report();
    stubFetch((request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/api/v1/admin/reports") {
        return Response.json(page([open]));
      }
      if (request.method === "GET" && url.pathname.includes("/bookmarks/")) {
        return Response.json(bookmark(open.bookmarkId, "Moderated bookmark"));
      }
      if (request.method === "PUT") return problem(503, "Moderation unavailable");
      return new Response(null, { status: 404 });
    });
    await prepareMessages();
    const { default: ReportsPage } = await import("../pages/admin/ReportsPage.vue");
    const wrapper = mount(ReportsPage);
    await settle();

    await wrapper
      .findAll("button")
      .find((button) => button.text() === "Action")
      ?.trigger("click");
    await settle();

    expect(wrapper.get("[role='alert']").text()).toBe("Moderation unavailable");
    expect(wrapper.text()).toContain("Moderated bookmark");
  });
});

describe("admin authorization", () => {
  beforeEach(() => {
    vi.resetModules();
  });

  it("denies regular users and exposes role-appropriate navigation", async () => {
    await prepareMessages();
    const auth = await import("../auth");
    const { default: AdminLayout } = await import("../pages/admin/AdminLayout.vue");

    auth.me.value = { username: "demo", roles: [] };
    const denied = mount(AdminLayout);
    expect(denied.get("[role='alert']").text()).toBe("403");
    denied.unmount();

    auth.me.value = { username: "moderator", roles: ["moderator"] };
    const moderator = mount(AdminLayout);
    await nextTick();
    expect(moderator.text()).toContain("Dashboard");
    expect(moderator.text()).toContain("Reports");
    expect(moderator.text()).not.toContain("Users");
    moderator.unmount();

    auth.me.value = { username: "admin", roles: ["admin"] };
    const admin = mount(AdminLayout);
    await nextTick();
    expect(admin.text()).toContain("Users");
    expect(admin.text()).toContain("Audit");
    expect(admin.text()).toContain("Messages");
  });
});
