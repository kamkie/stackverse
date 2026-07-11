import { expect, it, vi } from "vitest";

it("batches every supported console level, truncates large fields, and tolerates failures", async () => {
  vi.useFakeTimers();
  const originals = {
    log: console.log,
    info: console.info,
    warn: console.warn,
    error: console.error,
    debug: console.debug,
  };
  for (const level of Object.keys(originals) as (keyof typeof originals)[]) {
    console[level] = vi.fn();
  }
  const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", fetchMock);

  try {
    const { forwardConsoleToDevServer } = await import("./forwardConsoleToDevServer");
    forwardConsoleToDevServer();

    const circular: { self?: unknown } = {};
    circular.self = circular;
    console.log("hello", { answer: 42 });
    console.info("x".repeat(4_100));
    console.warn(circular);
    console.error(new Error("broken"));
    console.debug("trace");
    await vi.advanceTimersByTimeAsync(500);

    expect(fetchMock).toHaveBeenCalledOnce();
    const firstBatch = JSON.parse(
      (fetchMock.mock.calls[0][1] as RequestInit).body as string,
    ) as { level: string; message: string; time: string }[];
    expect(firstBatch.map((entry) => entry.level)).toEqual([
      "log",
      "info",
      "warn",
      "error",
      "debug",
    ]);
    expect(firstBatch[0].message).toBe('hello {"answer":42}');
    expect(firstBatch[1].message).toContain("[truncated 100 chars]");
    expect(firstBatch[1].message.length).toBeLessThan(4_100);
    expect(firstBatch[2].message).toBe("[object Object]");
    expect(firstBatch[3].message).toContain("Error: broken");
    expect(firstBatch.every((entry) => !Number.isNaN(Date.parse(entry.time)))).toBe(true);

    window.dispatchEvent(
      new ErrorEvent("error", {
        message: "uncaught",
        filename: "app.ts",
        lineno: 4,
        colno: 2,
      }),
    );
    const rejection = new Event("unhandledrejection");
    Object.defineProperty(rejection, "reason", { value: new Error("rejected") });
    window.dispatchEvent(rejection);
    window.dispatchEvent(new PageTransitionEvent("pagehide"));
    await Promise.resolve();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const secondBatch = JSON.parse(
      (fetchMock.mock.calls[1][1] as RequestInit).body as string,
    ) as { level: string; message: string }[];
    expect(secondBatch[0].message).toContain("Uncaught: uncaught app.ts:4:2");
    expect(secondBatch[1].message).toContain("Unhandled rejection: Error: rejected");

    fetchMock.mockRejectedValueOnce(new Error("dev server unavailable"));
    console.log("last line");
    await vi.advanceTimersByTimeAsync(500);
    await Promise.resolve();
    expect(fetchMock).toHaveBeenCalledTimes(3);
  } finally {
    Object.assign(console, originals);
  }
});
