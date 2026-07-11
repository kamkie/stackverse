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
import App from "../App.svelte";
import { route } from "../lib/route";
import { me, session } from "../lib/session";
import type { User } from "../lib/types";
import {
  defaultMessages,
  seedMessages,
  setIdentity,
  settle,
  stubFetch,
} from "./test-helpers";

function stubAppFetch(user: User | null): Request[] {
  return stubFetch((request) => {
    const url = new URL(request.url);
    if (url.pathname === "/api/v1/messages/bundle") {
      return Response.json({ language: "en", messages: defaultMessages });
    }
    if (url.pathname === "/auth/session") {
      return Response.json(
        user
          ? { authenticated: true, username: user.username }
          : { authenticated: false },
      );
    }
    if (url.pathname === "/api/v1/me" && user) return Response.json(user);
    if (url.pathname === "/api/v2/bookmarks")
      return Response.json({ items: [] });
    if (url.pathname === "/auth/logout" && request.method === "POST") {
      return new Response(null, { status: 204 });
    }
    return new Response(null, { status: 404 });
  });
}

beforeEach(() => {
  seedMessages();
  history.replaceState({}, "", "/feed");
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("application authorization shell", () => {
  it("denies regular users from the admin route", async () => {
    const user = { username: "demo", roles: [] };
    setIdentity(user);
    route.set("/admin/unknown");
    stubAppFetch(user);
    render(App);
    expect(await screen.findByRole("alert")).toHaveProperty(
      "textContent",
      "403",
    );
    expect(screen.queryByRole("link", { name: "Admin" })).toBeNull();
  });

  it("shows moderators only the dashboard and report administration", async () => {
    const user = { username: "moderator", roles: ["moderator"] };
    setIdentity(user);
    route.set("/admin/unknown");
    stubAppFetch(user);
    render(App);
    const adminNav = await screen.findByRole("navigation", { name: "Admin" });
    expect(
      within(adminNav).getByRole("link", { name: "Dashboard" }),
    ).toBeTruthy();
    expect(
      within(adminNav).getByRole("link", { name: "Reports" }),
    ).toBeTruthy();
    expect(within(adminNav).queryByRole("link", { name: "Users" })).toBeNull();
    expect(within(adminNav).queryByRole("link", { name: "Audit" })).toBeNull();
    expect(
      within(adminNav).queryByRole("link", { name: "Messages" }),
    ).toBeNull();
  });

  it("shows the complete admin navigation only to admins", async () => {
    const user = { username: "admin", roles: ["admin"] };
    setIdentity(user);
    route.set("/admin/unknown");
    stubAppFetch(user);
    render(App);
    const adminNav = await screen.findByRole("navigation", { name: "Admin" });
    expect(within(adminNav).getByRole("link", { name: "Users" })).toBeTruthy();
    expect(within(adminNav).getByRole("link", { name: "Audit" })).toBeTruthy();
    expect(
      within(adminNav).getByRole("link", { name: "Messages" }),
    ).toBeTruthy();
  });

  it("falls back to the public feed for logged-out private routes", async () => {
    setIdentity(null);
    route.set("/bookmarks");
    stubAppFetch(null);
    render(App);
    expect(await screen.findByRole("link", { name: "Log in" })).toHaveProperty(
      "pathname",
      "/auth/login",
    );
    expect(await screen.findByText("No matching bookmarks")).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "My bookmarks" })).toBeNull();
  });

  it("clears session state through the gateway logout boundary", async () => {
    const user = { username: "admin", roles: ["admin"] };
    setIdentity(user);
    route.set("/feed");
    const requests = stubAppFetch(user);
    render(App);
    await waitFor(() => {
      expect(
        requests.some(
          (request) => new URL(request.url).pathname === "/api/v1/me",
        ),
      ).toBe(true);
    });
    await settle();
    await fireEvent.click(
      await screen.findByRole("button", { name: "Log out" }),
    );
    await waitFor(() => {
      expect(get(session)).toEqual({ authenticated: false });
      expect(get(me)).toBeNull();
    });
    expect(
      requests.some(
        (request) =>
          request.method === "POST" &&
          new URL(request.url).pathname === "/auth/logout",
      ),
    ).toBe(true);
    expect(get(route)).toBe("/feed");
  });
});
