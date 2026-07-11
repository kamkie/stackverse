import { afterEach, describe, expect, it, vi } from "vitest";

const originalConsole = {
  log: console.log,
  info: console.info,
  warn: console.warn,
  error: console.error,
  debug: console.debug,
};

afterEach(() => {
  Object.assign(console, originalConsole);
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.resetModules();
});

describe("dev console forwarder", () => {
  it("batches bounded console and uncaught-error records without recursively logging failures", async () => {
    vi.useFakeTimers();
    const originalLog = vi.fn();
    console.log = originalLog;
    console.warn = vi.fn();
    const fetchMock = vi.fn().mockRejectedValue(new Error("dev server unavailable"));
    vi.stubGlobal("fetch", fetchMock);

    const { forwardConsoleToDevServer } = await import(
      "../dev/forwardConsoleToDevServer"
    );
    forwardConsoleToDevServer();

    const circular: Record<string, unknown> = {};
    circular.self = circular;
    console.log("structured", { ok: true }, circular);
    console.warn("x".repeat(4100));
    window.dispatchEvent(
      new ErrorEvent("error", {
        message: "render failed",
        filename: "app.tsx",
        lineno: 12,
        colno: 4,
      }),
    );
    const rejection = new Event("unhandledrejection");
    Object.defineProperty(rejection, "reason", {
      value: new Error("request failed"),
    });
    window.dispatchEvent(rejection);

    expect(originalLog).toHaveBeenCalledWith("structured", { ok: true }, circular);
    window.dispatchEvent(new Event("pagehide"));
    await Promise.resolve();

    expect(fetchMock).toHaveBeenCalledOnce();
    const [endpoint, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(endpoint).toBe("/__client-log");
    expect(init).toMatchObject({
      method: "POST",
      keepalive: true,
      headers: { "content-type": "application/json" },
    });
    const entries = JSON.parse(String(init.body)) as {
      level: string;
      message: string;
      time: string;
    }[];
    expect(entries).toHaveLength(4);
    expect(entries[0]).toMatchObject({
      level: "log",
      message: 'structured {"ok":true} [object Object]',
    });
    expect(entries[0]?.time).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(entries[1]?.message).toContain("… [truncated 100 chars]");
    expect(entries[1]?.message.startsWith("x".repeat(4000))).toBe(true);
    expect(entries[2]?.message).toContain("Uncaught: render failed app.tsx:12:4");
    expect(entries[3]?.message).toContain("Unhandled rejection: Error: request failed");

    await vi.runAllTimersAsync();
    expect(fetchMock).toHaveBeenCalledOnce();
  });
});
