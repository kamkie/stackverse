import { beforeEach, describe, expect, it, vi } from "vitest";

interface StreamEntryDouble {
  level: string;
  stream: { write?: (line: string) => void };
}

const loggingDoubles = vi.hoisted(() => ({
  config: { logLevel: "info", logFormat: "json", otelEnabled: false },
  streams: [] as StreamEntryDouble[],
  emit: vi.fn(),
  pretty: vi.fn(() => ({ write: vi.fn() })),
  multistream: vi.fn((streams: StreamEntryDouble[]) => {
    loggingDoubles.streams = streams;
    return { kind: "multistream" };
  }),
}));

vi.mock("./config.js", () => ({ config: loggingDoubles.config }));
vi.mock("@opentelemetry/api", () => ({
  trace: {
    getActiveSpan: vi.fn(() => ({
      spanContext: () => ({ traceId: "trace-id", spanId: "span-id" }),
    })),
  },
}));
vi.mock("@opentelemetry/api-logs", () => ({
  logs: { getLogger: vi.fn(() => ({ emit: loggingDoubles.emit })) },
  SeverityNumber: { TRACE: 1, DEBUG: 5, INFO: 9, WARN: 13, ERROR: 17, FATAL: 21 },
}));
vi.mock("pino-pretty", () => ({ default: loggingDoubles.pretty }));
vi.mock("pino", () => {
  const logger = {
    trace: vi.fn(),
    debug: vi.fn(),
    info: vi.fn(),
    warn: vi.fn(),
    error: vi.fn(),
    fatal: vi.fn(),
  };
  const pino = Object.assign(
    vi.fn(() => logger),
    {
      stdTimeFunctions: { isoTime: vi.fn() },
    },
  );
  return { pino, multistream: loggingDoubles.multistream };
});

beforeEach(() => {
  vi.resetModules();
  vi.clearAllMocks();
  loggingDoubles.streams = [];
  loggingDoubles.config.logLevel = "info";
  loggingDoubles.config.logFormat = "json";
  loggingDoubles.config.otelEnabled = false;
});

describe("OTLP log bridge", () => {
  it("maps structured console JSON into an OTLP record and drops null attributes", async () => {
    loggingDoubles.config.otelEnabled = true;
    await import("./logging.js");
    const otelStream = loggingDoubles.streams[1]?.stream;
    expect(otelStream?.write).toBeTypeOf("function");

    otelStream?.write?.(
      JSON.stringify({
        level: "warn",
        msg: "Keycloak unavailable",
        time: "2026-07-11T12:34:56.789Z",
        event: "dependency_call_failed",
        outcome: "failure",
        dependency: "keycloak",
        duration_ms: 42,
        omitted: null,
      }),
    );

    expect(loggingDoubles.emit).toHaveBeenCalledWith({
      severityNumber: 13,
      severityText: "WARN",
      body: "Keycloak unavailable",
      timestamp: new Date("2026-07-11T12:34:56.789Z"),
      attributes: {
        event: "dependency_call_failed",
        outcome: "failure",
        dependency: "keycloak",
        duration_ms: 42,
      },
    });
  });

  it("contains malformed console lines instead of crashing or exporting them", async () => {
    loggingDoubles.config.otelEnabled = true;
    await import("./logging.js");
    const otelStream = loggingDoubles.streams[1]?.stream;

    expect(() => otelStream?.write?.("not-json")).not.toThrow();
    expect(loggingDoubles.emit).not.toHaveBeenCalled();
  });

  it("keeps OTLP disabled and selects the explicit local text formatter", async () => {
    loggingDoubles.config.logLevel = "debug";
    loggingDoubles.config.logFormat = "text";

    await import("./logging.js");

    expect(loggingDoubles.pretty).toHaveBeenCalledWith({ destination: 1 });
    expect(loggingDoubles.streams).toHaveLength(1);
    expect(loggingDoubles.streams[0]?.level).toBe("debug");
  });
});
