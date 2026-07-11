import { afterEach, describe, expect, it, vi } from "vitest";
import { forwardConsoleToDevServer } from "./forwardConsoleToDevServer";

const levels = ["log", "info", "warn", "error", "debug"] as const;

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("development console forwarding", () => {
  it("serializes, bounds, and flushes browser diagnostics without breaking the console", async () => {
    vi.useFakeTimers();
    const originals = Object.fromEntries(
      levels.map((level) => [level, console[level]]),
    ) as Record<(typeof levels)[number], typeof console.log>;
    for (const level of levels) {
      console[level] = vi.fn() as typeof console.log;
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockRejectedValueOnce(new Error("dev server unavailable"));
    vi.stubGlobal("fetch", fetchMock);

    const circular: { self?: unknown } = {};
    circular.self = circular;

    try {
      forwardConsoleToDevServer();
      console.log("hello", { id: 7 });
      console.warn(new Error("warning stack"));
      console.debug(circular);
      console.info("x".repeat(4_010));

      await vi.advanceTimersByTimeAsync(500);

      window.dispatchEvent(
        new ErrorEvent("error", {
          message: "boom",
          filename: "app.ts",
          lineno: 12,
          colno: 3,
        }),
      );
      const rejection = new Event("unhandledrejection");
      Object.defineProperty(rejection, "reason", {
        value: new Error("rejected"),
      });
      window.dispatchEvent(rejection);
      window.dispatchEvent(new PageTransitionEvent("pagehide"));
      await Promise.resolve();
      await Promise.resolve();

      window.dispatchEvent(new PageTransitionEvent("pagehide"));
    } finally {
      for (const level of levels) console[level] = originals[level];
    }

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const [, firstInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(fetchMock.mock.calls[0][0]).toBe("/__client-log");
    expect(firstInit).toMatchObject({
      method: "POST",
      headers: { "content-type": "application/json" },
      keepalive: true,
    });
    const firstBatch = JSON.parse(String(firstInit.body)) as {
      level: string;
      message: string;
      time: string;
    }[];
    expect(firstBatch).toHaveLength(4);
    expect(firstBatch[0]).toMatchObject({
      level: "log",
      message: 'hello {"id":7}',
    });
    expect(firstBatch[1].message).toContain("warning stack");
    expect(firstBatch[2]).toMatchObject({
      level: "debug",
      message: "[object Object]",
    });
    expect(firstBatch[3].message).toMatch(
      /^x{4000}\.\.\. \[truncated 10 chars\]$/,
    );
    expect(
      firstBatch.every((entry) => !Number.isNaN(Date.parse(entry.time))),
    ).toBe(true);

    const [, secondInit] = fetchMock.mock.calls[1] as [string, RequestInit];
    const secondBatch = JSON.parse(String(secondInit.body)) as {
      level: string;
      message: string;
    }[];
    expect(secondBatch).toHaveLength(2);
    expect(secondBatch[0]).toMatchObject({
      level: "error",
      message: "Uncaught: boom app.ts:12:3",
    });
    expect(secondBatch[1].level).toBe("error");
    expect(secondBatch[1].message).toContain("Unhandled rejection:");
    expect(secondBatch[1].message).toContain("rejected");
  });
});
