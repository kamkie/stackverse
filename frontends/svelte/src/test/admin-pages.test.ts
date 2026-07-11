import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/svelte";
import { get } from "svelte/store";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { endOfDayIso } from "../lib/format";
import { route } from "../lib/route";
import type {
  AdminStats,
  AuditEntry,
  Message,
  UserAccount,
} from "../lib/types";
import AuditLogPage from "../pages/admin/AuditLogPage.svelte";
import DashboardPage from "../pages/admin/DashboardPage.svelte";
import MessagesPage from "../pages/admin/MessagesPage.svelte";
import UsersPage from "../pages/admin/UsersPage.svelte";
import {
  defaultMessages,
  installDialogPolyfill,
  page,
  problem,
  seedMessages,
  setIdentity,
  stubFetch,
  timestamp,
} from "./test-helpers";

beforeEach(() => {
  seedMessages({
    "ui.admin.chart.label": "Activity",
    "ui.admin.stats.active-users": "Active users",
    "ui.admin.stats.bookmarks": "Bookmarks",
    "ui.admin.stats.bookmarks-created": "Bookmarks created",
    "ui.admin.stats.hidden-bookmarks": "Hidden bookmarks",
    "ui.admin.stats.open-reports.other": "Open reports",
    "ui.admin.stats.public-bookmarks": "Public bookmarks",
    "ui.admin.stats.users": "Users",
    "ui.audit.action.placeholder": "Action filter",
  });
  installDialogPolyfill();
  setIdentity({ username: "admin", roles: ["admin"] });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("admin users", () => {
  it("prevents self-blocking in the UI, validates reasons, and changes status", async () => {
    const self: UserAccount = {
      username: "admin",
      firstSeen: timestamp,
      lastSeen: timestamp,
      status: "active",
      bookmarkCount: 1,
    };
    const target: UserAccount = {
      username: "target user",
      firstSeen: timestamp,
      lastSeen: timestamp,
      status: "active",
      bookmarkCount: 2,
    };
    const blocked: UserAccount = {
      username: "blocked-user",
      firstSeen: timestamp,
      lastSeen: timestamp,
      status: "blocked",
      blockedReason: "Abuse",
      bookmarkCount: 0,
    };
    let users = [self, target, blocked];
    const writes: { path: string; body: unknown }[] = [];
    stubFetch(async (request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/api/v1/admin/users") {
        return Response.json(page(users));
      }
      if (request.method === "PUT" && url.pathname.endsWith("/status")) {
        const body = (await request.clone().json()) as {
          status: "active" | "blocked";
          reason?: string;
        };
        writes.push({ path: url.pathname, body });
        const username = decodeURIComponent(
          url.pathname.split("/").at(-2) ?? "",
        );
        users = users.map((user) =>
          user.username === username
            ? {
                ...user,
                status: body.status,
                blockedReason:
                  body.status === "blocked" ? body.reason : undefined,
              }
            : user,
        );
        return Response.json(
          users.find((user) => user.username === username) as UserAccount,
        );
      }
      return new Response(null, { status: 404 });
    });

    render(UsersPage);
    const selfRow = (await screen.findByText("admin")).closest(
      "tr",
    ) as HTMLElement;
    expect(within(selfRow).queryByRole("button", { name: "Block" })).toBeNull();
    const targetRow = screen
      .getByText("target user")
      .closest("tr") as HTMLElement;
    await fireEvent.click(
      within(targetRow).getByRole("button", { name: "Block" }),
    );
    const dialog = await screen.findByRole("dialog");
    await fireEvent.submit(dialog.querySelector("form") as HTMLFormElement);
    expect(
      await within(dialog).findByText("A reason is required"),
    ).toBeTruthy();
    expect(writes).toHaveLength(0);
    await fireEvent.input(within(dialog).getByLabelText(/Reason/), {
      target: { value: "Repeated abuse" },
    });
    await fireEvent.submit(dialog.querySelector("form") as HTMLFormElement);
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).toEqual({
      path: "/api/v1/admin/users/target%20user/status",
      body: { status: "blocked", reason: "Repeated abuse" },
    });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    const blockedRow = screen
      .getByText("blocked-user")
      .closest("tr") as HTMLElement;
    await fireEvent.click(
      within(blockedRow).getByRole("button", { name: "Unblock" }),
    );
    await waitFor(() => expect(writes).toHaveLength(2));
    expect(writes[1]).toEqual({
      path: "/api/v1/admin/users/blocked-user/status",
      body: { status: "active" },
    });
  });
});

describe("admin messages", () => {
  it("maps validation errors and completes create, edit, and delete flows", async () => {
    const existing: Message = {
      id: "00000000-0000-4000-8000-000000000401",
      key: "ui.existing",
      language: "en",
      text: "Existing",
      createdAt: timestamp,
      updatedAt: timestamp,
    };
    const created: Message = {
      id: "00000000-0000-4000-8000-000000000402",
      key: "ui.created",
      language: "pl",
      text: "Utworzono",
      description: "Polish copy",
      createdAt: timestamp,
      updatedAt: timestamp,
    };
    let messages = [existing];
    let rejectCreate = true;
    const writes: { method: string; path: string; body?: unknown }[] = [];
    stubFetch(async (request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/api/v1/messages") {
        return Response.json(page(messages));
      }
      if (
        request.method === "GET" &&
        url.pathname === "/api/v1/messages/bundle"
      ) {
        return Response.json({ language: "en", messages: defaultMessages });
      }
      if (request.method === "POST" && url.pathname === "/api/v1/messages") {
        if (rejectCreate) {
          return problem(400, "Invalid message", [
            {
              field: "key",
              messageKey: "validation.key.required",
              message: "Localized key error",
            },
          ]);
        }
        const body = await request.clone().json();
        writes.push({ method: request.method, path: url.pathname, body });
        messages = [...messages, created];
        return Response.json(created, { status: 201 });
      }
      if (request.method === "PUT" && url.pathname.endsWith(created.id)) {
        const body = await request.clone().json();
        writes.push({ method: request.method, path: url.pathname, body });
        messages = messages.map((message) =>
          message.id === created.id
            ? { ...created, text: "Zaktualizowano" }
            : message,
        );
        return Response.json(
          messages.find((message) => message.id === created.id),
        );
      }
      if (request.method === "DELETE" && url.pathname.endsWith(created.id)) {
        writes.push({ method: request.method, path: url.pathname });
        messages = messages.filter((message) => message.id !== created.id);
        return new Response(null, { status: 204 });
      }
      return new Response(null, { status: 404 });
    });
    const toast = vi.fn();

    render(MessagesPage, { toast });
    expect(await screen.findByText("ui.existing")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Add bookmark" }));
    let dialog = await screen.findByRole("dialog");
    await fireEvent.submit(dialog.querySelector("form") as HTMLFormElement);
    expect(await within(dialog).findByText("Localized key error")).toBeTruthy();

    rejectCreate = false;
    dialog = screen.getByRole("dialog");
    await fireEvent.input(within(dialog).getByLabelText(/Key/), {
      target: { value: created.key },
    });
    await fireEvent.change(within(dialog).getByLabelText("Language"), {
      target: { value: created.language },
    });
    await fireEvent.input(within(dialog).getByLabelText("Text"), {
      target: { value: created.text },
    });
    await fireEvent.input(within(dialog).getByLabelText("Description"), {
      target: { value: created.description },
    });
    await fireEvent.submit(dialog.querySelector("form") as HTMLFormElement);
    expect(await screen.findByText("ui.created")).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    let createdRow = screen
      .getByText("ui.created")
      .closest("tr") as HTMLElement;
    await fireEvent.click(
      within(createdRow).getByRole("button", { name: "Edit" }),
    );
    dialog = await screen.findByRole("dialog");
    await fireEvent.input(within(dialog).getByLabelText("Text"), {
      target: { value: "Zaktualizowano" },
    });
    await fireEvent.submit(dialog.querySelector("form") as HTMLFormElement);
    expect(await screen.findByText("Zaktualizowano")).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    createdRow = screen.getByText("ui.created").closest("tr") as HTMLElement;
    await fireEvent.click(
      within(createdRow).getByRole("button", { name: "Delete" }),
    );
    dialog = await screen.findByRole("dialog");
    await fireEvent.submit(dialog.querySelector("form") as HTMLFormElement);
    await waitFor(() => expect(screen.queryByText("ui.created")).toBeNull());

    expect(writes).toEqual([
      {
        method: "POST",
        path: "/api/v1/messages",
        body: {
          key: created.key,
          language: created.language,
          text: created.text,
          description: created.description,
        },
      },
      {
        method: "PUT",
        path: `/api/v1/messages/${created.id}`,
        body: {
          key: created.key,
          language: created.language,
          text: "Zaktualizowano",
          description: created.description,
        },
      },
      { method: "DELETE", path: `/api/v1/messages/${created.id}` },
    ]);
    expect(toast.mock.calls.map(([message]) => message)).toEqual([
      "Message created",
      "Message updated",
      "Message deleted",
    ]);
  });
});

describe("admin audit and dashboard", () => {
  it("synchronizes debounced and date filters into contract query parameters", async () => {
    const entry: AuditEntry = {
      id: "00000000-0000-4000-8000-000000000501",
      actor: "moderator",
      action: "report.resolved",
      targetType: "report",
      targetId: "00000000-0000-4000-8000-000000000301",
      createdAt: timestamp,
    };
    const requests = stubFetch(() => Response.json(page([entry])));
    render(AuditLogPage);
    expect(await screen.findByText("report.resolved")).toBeTruthy();
    await fireEvent.input(screen.getByPlaceholderText("Actor"), {
      target: { value: "moderator" },
    });
    await waitFor(
      () => {
        expect(
          requests.some(
            (request) =>
              new URL(request.url).searchParams.get("actor") === "moderator",
          ),
        ).toBe(true);
      },
      { timeout: 1000 },
    );
    await fireEvent.change(screen.getByLabelText("From"), {
      target: { value: "2026-07-01" },
    });
    await fireEvent.change(screen.getByLabelText("To"), {
      target: { value: "2026-07-02" },
    });
    await waitFor(() => {
      const url = new URL(requests.at(-1)?.url ?? location.origin);
      expect(url.searchParams.get("from")).toBe(
        new Date("2026-07-01T00:00:00").toISOString(),
      );
      expect(url.searchParams.get("to")).toBe(endOfDayIso("2026-07-02"));
    });
    await fireEvent.click(
      screen.getByRole("button", { name: "Clear filters" }),
    );
    await waitFor(() => {
      expect(new URL(requests.at(-1)?.url ?? location.origin).search).toBe(
        "?page=0",
      );
    });
  });

  it("renders aggregate stats and routes the report total through the SPA", async () => {
    const stats: AdminStats = {
      totals: {
        users: 5,
        bookmarks: 8,
        publicBookmarks: 3,
        hiddenBookmarks: 1,
        openReports: 2,
      },
      daily: [
        { date: "2026-07-10", bookmarksCreated: 0, activeUsers: 1 },
        { date: "2026-07-11", bookmarksCreated: 2, activeUsers: 3 },
      ],
      topTags: [],
    };
    stubFetch(() => Response.json(stats));
    route.set("/admin");
    const { container } = render(DashboardPage);
    expect(await screen.findByText("8")).toBeTruthy();
    expect(container.querySelectorAll(".sv-chart-bar")).toHaveLength(4);
    await fireEvent.click(
      container.querySelector(".sv-stat--link") as HTMLAnchorElement,
    );
    expect(location.pathname).toBe("/admin/reports");
    expect(get(route)).toBe("/admin/reports");
  });
});
