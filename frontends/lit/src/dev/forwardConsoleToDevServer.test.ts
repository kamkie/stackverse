import { forwardConsoleToDevServer } from "./forwardConsoleToDevServer";

const levels = ["log", "info", "warn", "error", "debug"] as const;

describe("forwardConsoleToDevServer", () => {
  const originalConsole = Object.fromEntries(
    levels.map((level) => [level, console[level]]),
  ) as Record<(typeof levels)[number], typeof console.log>;

  afterEach(() => {
    for (const level of levels) console[level] = originalConsole[level];
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("keeps original console behavior and forwards sanitized batches", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-05T12:00:00.000Z"));

    const originalWarn = vi.fn();
    console.warn = originalWarn;
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(null, { status: 204 }));

    forwardConsoleToDevServer();
    console.warn("first line\nsecond line", { ok: true });

    expect(originalWarn).toHaveBeenCalledWith("first line\nsecond line", {
      ok: true,
    });
    await vi.advanceTimersByTimeAsync(120);

    expect(fetchMock).toHaveBeenCalledWith("/__client-log", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: expect.any(String),
      keepalive: true,
    });
    const body = JSON.parse(String(fetchMock.mock.calls[0]![1]?.body)) as {
      level: string;
      message: string;
      time: string;
    }[];
    expect(body).toEqual([
      {
        level: "warn",
        message: 'first line\\nsecond line {"ok":true}',
        time: "2026-07-05T12:00:00.000Z",
      },
    ]);
  });

  it("describes errors and ignores forwarding failures", async () => {
    vi.useFakeTimers();
    console.error = vi.fn();
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockRejectedValue(new Error("offline"));

    forwardConsoleToDevServer();
    console.error(new TypeError("boom"));
    await vi.advanceTimersByTimeAsync(120);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const body = JSON.parse(String(fetchMock.mock.calls[0]![1]?.body)) as {
      message: string;
    }[];
    expect(body[0]!.message).toBe("TypeError: boom");
  });
});
