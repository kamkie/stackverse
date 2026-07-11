import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentPath, goto, installRouteListener, normalize } from "./route";

beforeEach(() => {
  history.replaceState({}, "", "/feed");
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("route normalization and navigation", () => {
  it("maps the root and trailing slashes to stable application routes", () => {
    expect(normalize("/")).toBe("/feed");
    expect(normalize("/admin/reports///")).toBe("/admin/reports");
    expect(normalize("///")).toBe("/feed");
  });

  it("pushes changed routes, avoids duplicates, and supports replacement", () => {
    const push = vi.spyOn(history, "pushState");
    const replace = vi.spyOn(history, "replaceState");

    expect(goto("/feed/")).toBe("/feed");
    expect(push).not.toHaveBeenCalled();

    expect(goto("/reports/")).toBe("/reports");
    expect(currentPath()).toBe("/reports");
    expect(push).toHaveBeenCalledWith({}, "", "/reports");

    expect(goto("/admin/", true)).toBe("/admin");
    expect(currentPath()).toBe("/admin");
    expect(replace).toHaveBeenCalledWith({}, "", "/admin");
  });
});

describe("route listener", () => {
  it("redirects the bare root, follows popstate, and removes its listener", () => {
    history.replaceState({}, "", "/");
    const onChange = vi.fn();

    const uninstall = installRouteListener(onChange);

    expect(location.pathname).toBe("/feed");
    expect(onChange).toHaveBeenLastCalledWith("/feed");

    history.pushState({}, "", "/reports/");
    window.dispatchEvent(new PopStateEvent("popstate"));
    expect(onChange).toHaveBeenLastCalledWith("/reports");

    const callsBeforeCleanup = onChange.mock.calls.length;
    uninstall();
    history.pushState({}, "", "/admin");
    window.dispatchEvent(new PopStateEvent("popstate"));
    expect(onChange).toHaveBeenCalledTimes(callsBeforeCleanup);
  });
});
