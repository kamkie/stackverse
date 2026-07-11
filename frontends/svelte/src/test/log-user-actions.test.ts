import { afterEach, describe, expect, it, vi } from "vitest";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("dev user-action logging privacy", () => {
  it("logs labels, context, navigation, and API outcomes without field values or bodies", async () => {
    vi.resetModules();
    history.replaceState({}, "", "/profile");
    const originalDebug = console.debug;
    const originalPushState = history.pushState;
    const originalReplaceState = history.replaceState;
    const debug = vi.fn();
    console.debug = debug;
    const upstream = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", upstream);

    try {
      const { logUserActions } = await import("../dev/logUserActions");
      logUserActions();
      const form = document.createElement("form");
      form.dataset.ctx = "user:demo";
      const password = document.createElement("input");
      password.type = "password";
      password.name = "password";
      password.value = "super-secret-value";
      const submit = document.createElement("button");
      submit.type = "submit";
      submit.textContent = "Save profile";
      form.append(password, submit);
      document.body.append(form);

      password.dispatchEvent(new MouseEvent("click", { bubbles: true }));
      form.dispatchEvent(
        new SubmitEvent("submit", { bubbles: true, submitter: submit }),
      );
      history.pushState({}, "", "/profile/saved");
      await fetch("/api/v1/bookmarks", {
        method: "POST",
        body: JSON.stringify({ password: "super-secret-value" }),
      });
      await fetch("https://example.net/not-app-traffic", {
        method: "POST",
        body: "super-secret-value",
      });

      const lines = debug.mock.calls.flat().join("\n");
      expect(lines).toContain('[action] click input[type=password] "password"');
      expect(lines).toContain("in user:demo @ /profile");
      expect(lines).toContain(
        '[action] submit form "Save profile" via button "Save profile"',
      );
      expect(lines).toContain("[nav] push /profile -> /profile/saved");
      expect(lines).toContain("[api] POST /api/v1/bookmarks -> 204");
      expect(lines).not.toContain("super-secret-value");
      expect(lines).not.toContain("example.net");
      expect(upstream).toHaveBeenCalledTimes(2);
    } finally {
      console.debug = originalDebug;
      history.pushState = originalPushState;
      history.replaceState = originalReplaceState;
      document.body.innerHTML = "";
    }
  });
});
