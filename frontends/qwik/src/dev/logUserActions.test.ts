import { afterEach, describe, expect, it, vi } from "vitest";
import { logUserActions } from "./logUserActions";

const nativePushState = history.pushState;
const nativeReplaceState = history.replaceState;

afterEach(() => {
  history.pushState = nativePushState;
  history.replaceState = nativeReplaceState;
  document.body.replaceChildren();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("development user-action logging", () => {
  it("records useful context and request outcomes without reading field values or bodies", async () => {
    nativeReplaceState.call(history, {}, "", "/logging-test");

    const originalFetch = vi.fn(async (input: RequestInfo | URL) => {
      const target = input instanceof Request ? input.url : String(input);
      if (target.includes("/api/fail")) throw new Error("offline");
      return new Response(null, { status: 201 });
    });
    vi.stubGlobal("fetch", originalFetch);
    const debug = vi.spyOn(console, "debug").mockImplementation(() => {});

    logUserActions();
    const installedFetch = globalThis.fetch;
    logUserActions();
    expect(globalThis.fetch).toBe(installedFetch);

    const userContext = document.createElement("section");
    userContext.dataset.ctx = "user:alice";
    const reportContext = document.createElement("div");
    reportContext.dataset.ctx = "report:r-1";
    const privateInput = document.createElement("input");
    privateInput.name = "reason";
    privateInput.value = "super-secret-field-value";
    reportContext.append(privateInput);
    userContext.append(reportContext);
    document.body.append(userContext);

    privateInput.dispatchEvent(new MouseEvent("click", { bubbles: true }));

    const deadZone = document.createElement("span");
    deadZone.textContent = "Dead zone";
    document.body.append(deadZone);
    deadZone.dispatchEvent(new MouseEvent("click", { bubbles: true }));

    const form = document.createElement("form");
    form.dataset.ctx = "bookmark:b-1";
    const title = document.createElement("input");
    title.name = "title";
    title.value = "private bookmark title";
    const submit = document.createElement("button");
    submit.type = "submit";
    submit.textContent = "Save";
    form.append(title, submit);
    document.body.append(form);
    form.dispatchEvent(
      new SubmitEvent("submit", { bubbles: true, submitter: submit }),
    );

    history.pushState({}, "", "/reports");
    history.replaceState({}, "", "/admin/reports");
    nativePushState.call(history, {}, "", "/feed");
    window.dispatchEvent(new PopStateEvent("popstate"));

    await expect(
      globalThis.fetch("/api/v1/bookmarks", {
        method: "post",
        body: "super-secret-request-body",
      }),
    ).resolves.toHaveProperty("status", 201);
    await globalThis.fetch(
      new Request(new URL("/auth/logout", location.origin), { method: "POST" }),
    );
    await globalThis.fetch("https://example.com/outside");
    await globalThis.fetch("http://[");
    await expect(globalThis.fetch("/api/fail")).rejects.toThrow("offline");

    const lines = debug.mock.calls.map((args) => args.join(" "));

    history.pushState = nativePushState;
    history.replaceState = nativeReplaceState;

    expect(lines).toContain(
      '[action] click input[type=text] "reason" in report:r-1 in user:alice @ /logging-test',
    );
    expect(lines).toContain(
      '[action] click span "Dead zone" (non-interactive) @ /logging-test',
    );
    expect(
      lines.some(
        (line) =>
          line.startsWith("[action] submit form") &&
          line.includes('via button "Save"') &&
          line.endsWith("in bookmark:b-1 @ /logging-test"),
      ),
    ).toBe(true);
    expect(lines.some((line) => line.startsWith("[nav] push "))).toBe(true);
    expect(lines.some((line) => line.startsWith("[nav] replace "))).toBe(true);
    expect(lines.some((line) => line.startsWith("[nav] pop "))).toBe(true);
    expect(
      lines.some((line) =>
        /^\[api\] POST \/api\/v1\/bookmarks -> 201 \(\d+ms\)$/.test(line),
      ),
    ).toBe(true);
    expect(
      lines.some((line) =>
        /^\[api\] POST \/auth\/logout -> 201 \(\d+ms\)$/.test(line),
      ),
    ).toBe(true);
    expect(
      lines.some((line) =>
        /^\[api\] GET \/api\/fail -> network error \(\d+ms\)$/.test(line),
      ),
    ).toBe(true);
    expect(lines.join("\n")).not.toContain("super-secret-field-value");
    expect(lines.join("\n")).not.toContain("private bookmark title");
    expect(lines.join("\n")).not.toContain("super-secret-request-body");
    expect(lines.join("\n")).not.toContain("example.com/outside");
  });
});
