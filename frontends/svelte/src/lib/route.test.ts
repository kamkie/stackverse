import { get } from "svelte/store";
import { beforeEach, describe, expect, it, vi } from "vitest";

async function loadRoute(path: string) {
  history.replaceState({}, "", path);
  vi.resetModules();
  return import("./route");
}

beforeEach(() => {
  history.replaceState({}, "", "/feed");
});

describe("route", () => {
  it("normalizes root and trailing slashes", async () => {
    const rootRoute = await loadRoute("/");
    expect(rootRoute.currentPath()).toBe("/feed");
    expect(get(rootRoute.route)).toBe("/feed");

    const nestedRoute = await loadRoute("/admin/users/");
    expect(nestedRoute.currentPath()).toBe("/admin/users");
    expect(get(nestedRoute.route)).toBe("/admin/users");
  });

  it("updates history and the route store on navigation", async () => {
    const router = await loadRoute("/feed");

    router.goto("/admin/reports/");

    expect(location.pathname).toBe("/admin/reports");
    expect(get(router.route)).toBe("/admin/reports");
  });

  it("tracks browser popstate events and removes the listener on cleanup", async () => {
    const router = await loadRoute("/feed");
    const cleanup = router.installRouteListener();

    history.pushState({}, "", "/my-reports");
    window.dispatchEvent(new PopStateEvent("popstate"));
    expect(get(router.route)).toBe("/my-reports");

    cleanup();
    history.pushState({}, "", "/admin/messages");
    window.dispatchEvent(new PopStateEvent("popstate"));
    expect(get(router.route)).toBe("/my-reports");
  });

  it("replaces the root path with the public feed on listener install", async () => {
    const router = await loadRoute("/");

    const cleanup = router.installRouteListener();

    expect(location.pathname).toBe("/feed");
    expect(get(router.route)).toBe("/feed");
    cleanup();
  });
});
