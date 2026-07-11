import { afterEach, describe, expect, it, vi } from "vitest";

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("dev console forwarding", () => {
  it("batches supported levels, serializes failures safely, and caps payloads", async () => {
    vi.resetModules();
    vi.useFakeTimers();
    const originals = {
      log: console.log,
      info: console.info,
      warn: console.warn,
      error: console.error,
      debug: console.debug,
    };
    console.log = vi.fn();
    console.info = vi.fn();
    console.warn = vi.fn();
    console.error = vi.fn();
    console.debug = vi.fn();
    const requests: { input: RequestInfo | URL; init?: RequestInit }[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        requests.push({ input, init });
        return new Response(null, { status: 204 });
      }),
    );

    try {
      const { forwardConsoleToDevServer } =
        await import("../dev/forwardConsoleToDevServer");
      forwardConsoleToDevServer();
      const circular: Record<string, unknown> = {};
      circular.self = circular;
      console.info("hello", { ok: true });
      console.warn(circular);
      console.error(new Error("boom"));
      console.debug("x".repeat(4500));
      await vi.advanceTimersByTimeAsync(500);

      expect(requests).toHaveLength(1);
      expect(String(requests[0]?.input)).toBe("/__client-log");
      expect(requests[0]?.init?.method).toBe("POST");
      expect(requests[0]?.init?.keepalive).toBe(true);
      const batch = JSON.parse(String(requests[0]?.init?.body)) as {
        level: string;
        message: string;
        time: string;
      }[];
      expect(batch.map((entry) => entry.level)).toEqual([
        "info",
        "warn",
        "error",
        "debug",
      ]);
      expect(batch[0]?.message).toBe('hello {"ok":true}');
      expect(batch[1]?.message).toBe("[object Object]");
      expect(batch[2]?.message).toContain("Error: boom");
      expect(batch[3]?.message).toContain("[truncated 500 chars]");
      expect(
        batch.every((entry) => !Number.isNaN(Date.parse(entry.time))),
      ).toBe(true);
    } finally {
      console.log = originals.log;
      console.info = originals.info;
      console.warn = originals.warn;
      console.error = originals.error;
      console.debug = originals.debug;
    }
  });
});
