import { expect, it, vi } from "vitest";

it("logs structure and request outcomes without leaking field values or query strings", async () => {
  const originalPushState = history.pushState;
  const originalReplaceState = history.replaceState;
  const debug = vi.spyOn(console, "debug").mockImplementation(() => {});
  const originalFetch = vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(input instanceof Request ? input.url : String(input), location.origin);
    if (url.pathname === "/api/fail") throw new Error("offline");
    return new Response(null, { status: url.pathname === "/api/denied" ? 403 : 204 });
  });
  vi.stubGlobal("fetch", originalFetch);

  try {
    history.replaceState({}, "", "/feed");
    const { logUserActions } = await import("./logUserActions");
    logUserActions();
    logUserActions();

    const context = document.createElement("section");
    context.dataset.ctx = "bookmark:123";
    const form = document.createElement("form");
    const input = document.createElement("input");
    input.name = "search";
    input.value = "private-field-value";
    const button = document.createElement("button");
    button.type = "submit";
    button.textContent = "Save";
    form.append(input, button);
    context.append(form);
    document.body.append(context);

    input.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    const submit = new SubmitEvent("submit", { bubbles: true, cancelable: true });
    Object.defineProperty(submit, "submitter", { value: button });
    form.dispatchEvent(submit);
    document.body.dispatchEvent(new MouseEvent("click", { bubbles: true }));

    history.pushState({}, "", "/reports?token=private-query-value");
    history.replaceState({}, "", "/feed?token=private-query-value");
    originalPushState.call(history, {}, "", "/bookmarks");
    window.dispatchEvent(new PopStateEvent("popstate"));

    await fetch("/api/denied?q=private-query-value");
    await expect(fetch("/api/fail?q=private-query-value")).rejects.toThrow("offline");
    await fetch("https://example.net/api/ignored?q=private-query-value");

    const lines = debug.mock.calls.map(([line]) => String(line));
    expect(lines.some((line) => line.includes('[action] click input[type=text] "search" in bookmark:123'))).toBe(true);
    expect(lines.some((line) => line.includes('[action] submit form "Save" via button "Save" in bookmark:123'))).toBe(true);
    expect(lines.some((line) => line.includes("(non-interactive)"))).toBe(true);
    expect(lines.some((line) => line.includes("[nav] push /feed -> /reports"))).toBe(true);
    expect(lines.some((line) => line.includes("[api] GET /api/denied -> 403"))).toBe(true);
    expect(lines.some((line) => line.includes("[api] GET /api/fail -> network error"))).toBe(true);
    expect(lines.join("\n")).not.toContain("private-field-value");
    expect(lines.join("\n")).not.toContain("private-query-value");
    expect(originalFetch).toHaveBeenCalledTimes(3);
  } finally {
    history.pushState = originalPushState;
    history.replaceState = originalReplaceState;
  }
});
