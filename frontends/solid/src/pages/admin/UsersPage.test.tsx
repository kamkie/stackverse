import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { setMe } from "../../lib/session";
import type { UserAccount } from "../../lib/types";
import { jsonResponse, page, readyI18n, userAccount } from "../../test/fixtures";
import UsersPage from "./UsersPage";

beforeEach(() => {
  readyI18n();
  setMe({ username: "admin", roles: ["admin", "moderator"] });
  document.cookie = "XSRF-TOKEN=admin-token; path=/";
});

describe("UsersPage", () => {
  it("protects self-blocking, validates the reason, and sends block/unblock payloads", async () => {
    let users: UserAccount[] = [
      userAccount({ username: "admin" }),
      userAccount({ username: "other user" }),
      userAccount({ username: "blocked", status: "blocked", blockedReason: "abuse" }),
    ];
    const mutations: { path: string; body: unknown }[] = [];
    const fetchMock = vi.fn(async (input: URL, init?: RequestInit) => {
      if (input.pathname === "/api/v1/admin/users") {
        return jsonResponse(page(users));
      }
      if (input.pathname.endsWith("/status") && init?.method === "PUT") {
        const body = JSON.parse(init.body as string) as {
          status: "active" | "blocked";
          reason?: string;
        };
        mutations.push({ path: input.pathname, body });
        const username = decodeURIComponent(input.pathname.split("/").at(-2)!);
        users = users.map((user) =>
          user.username === username
            ? {
                ...user,
                status: body.status,
                ...(body.status === "blocked"
                  ? { blockedReason: body.reason }
                  : { blockedReason: undefined }),
              }
            : user,
        );
        return jsonResponse(users.find((user) => user.username === username));
      }
      throw new Error(`Unexpected request: ${input}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    render(() => <UsersPage />);

    await waitFor(() =>
      expect(document.querySelector('[data-ctx="user:admin"]')).not.toBeNull(),
    );
    const ownRow = document.querySelector<HTMLTableRowElement>('[data-ctx="user:admin"]')!;
    expect(within(ownRow).queryByRole("button", { name: "block" })).toBeNull();

    await fireEvent.click(screen.getByRole("button", { name: "block" }));
    const dialog = screen.getByRole("dialog");
    await fireEvent.submit(dialog.querySelector("form")!);
    expect(await within(dialog).findByText("required")).toBeTruthy();
    expect(mutations).toHaveLength(0);

    await fireEvent.input(within(dialog).getByLabelText(/^reason/), {
      target: { value: "Repeated abuse" },
    });
    await fireEvent.submit(dialog.querySelector("form")!);
    await waitFor(() => expect(mutations).toHaveLength(1));
    expect(mutations[0]).toEqual({
      path: "/api/v1/admin/users/other%20user/status",
      body: { status: "blocked", reason: "Repeated abuse" },
    });

    await waitFor(() =>
      expect(document.querySelector('[data-ctx="user:blocked"]')).not.toBeNull(),
    );
    const blockedRow = document.querySelector<HTMLTableRowElement>(
      '[data-ctx="user:blocked"]',
    )!;
    await fireEvent.click(within(blockedRow).getByRole("button", { name: "unblock" }));
    await waitFor(() => expect(mutations).toHaveLength(2));
    expect(mutations[1]).toEqual({
      path: "/api/v1/admin/users/blocked/status",
      body: { status: "active" },
    });
  });

  it("renders a privileged API failure instead of a user table", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse({ title: "Forbidden", detail: "Admin role required" }, { status: 403 }),
      ),
    );
    render(() => <UsersPage />);

    expect((await screen.findByRole("alert")).textContent).toContain("Admin role required");
    expect(screen.queryByRole("table")).toBeNull();
  });
});
