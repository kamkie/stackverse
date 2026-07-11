import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

interface HttpInstrumentationOptions {
  ignoreIncomingRequestHook: (request: { url?: string }) => boolean;
}

const otelDoubles = vi.hoisted(() => ({
  config: { otelEnabled: false },
  sdkOptions: undefined as Record<string, unknown> | undefined,
  httpOptions: undefined as HttpInstrumentationOptions | undefined,
  metricReaderOptions: undefined as Record<string, unknown> | undefined,
  logProcessorOptions: undefined as Record<string, unknown> | undefined,
  start: vi.fn(),
  shutdown: vi.fn(),
  traceExporter: { kind: "trace-exporter" },
  metricExporter: { kind: "metric-exporter" },
  logExporter: { kind: "log-exporter" },
}));

vi.mock("./config.js", () => ({ config: otelDoubles.config }));
vi.mock("@opentelemetry/sdk-node", () => ({
  NodeSDK: class {
    constructor(options: Record<string, unknown>) {
      otelDoubles.sdkOptions = options;
    }

    start(): void {
      otelDoubles.start();
    }

    shutdown(): Promise<void> {
      return otelDoubles.shutdown();
    }
  },
}));
vi.mock("@opentelemetry/instrumentation-http", () => ({
  HttpInstrumentation: class {
    constructor(options: HttpInstrumentationOptions) {
      otelDoubles.httpOptions = options;
    }
  },
}));
vi.mock("@opentelemetry/exporter-trace-otlp-grpc", () => ({
  OTLPTraceExporter: class {
    constructor() {
      return otelDoubles.traceExporter;
    }
  },
}));
vi.mock("@opentelemetry/exporter-metrics-otlp-grpc", () => ({
  OTLPMetricExporter: class {
    constructor() {
      return otelDoubles.metricExporter;
    }
  },
}));
vi.mock("@opentelemetry/exporter-logs-otlp-grpc", () => ({
  OTLPLogExporter: class {
    constructor() {
      return otelDoubles.logExporter;
    }
  },
}));
vi.mock("@opentelemetry/sdk-metrics", () => ({
  PeriodicExportingMetricReader: class {
    constructor(options: Record<string, unknown>) {
      otelDoubles.metricReaderOptions = options;
    }
  },
}));
vi.mock("@opentelemetry/sdk-logs", () => ({
  BatchLogRecordProcessor: class {
    constructor(options: Record<string, unknown>) {
      otelDoubles.logProcessorOptions = options;
    }
  },
}));

beforeEach(() => {
  vi.resetModules();
  vi.clearAllMocks();
  otelDoubles.config.otelEnabled = false;
  otelDoubles.sdkOptions = undefined;
  otelDoubles.httpOptions = undefined;
  otelDoubles.metricReaderOptions = undefined;
  otelDoubles.logProcessorOptions = undefined;
  otelDoubles.shutdown.mockResolvedValue(undefined);
  vi.stubEnv("OTEL_SERVICE_NAME", undefined);
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("OpenTelemetry lifecycle", () => {
  it("stays completely disabled by default", async () => {
    const { shutdownTelemetry } = await import("./otel.js");

    expect(otelDoubles.sdkOptions).toBeUndefined();
    expect(otelDoubles.start).not.toHaveBeenCalled();
    await expect(shutdownTelemetry()).resolves.toBeUndefined();
    expect(otelDoubles.shutdown).not.toHaveBeenCalled();
  });

  it("wires traces, metrics, logs, and probe-noise filtering when enabled", async () => {
    otelDoubles.config.otelEnabled = true;
    const { shutdownTelemetry } = await import("./otel.js");

    expect(otelDoubles.start).toHaveBeenCalledOnce();
    expect(otelDoubles.sdkOptions).toMatchObject({
      serviceName: "stackverse-gateway-node-fastify",
      traceExporter: otelDoubles.traceExporter,
    });
    expect(otelDoubles.metricReaderOptions).toEqual({ exporter: otelDoubles.metricExporter });
    expect(otelDoubles.logProcessorOptions).toEqual({ exporter: otelDoubles.logExporter });
    expect(otelDoubles.httpOptions?.ignoreIncomingRequestHook({ url: "/healthz" })).toBe(true);
    expect(otelDoubles.httpOptions?.ignoreIncomingRequestHook({ url: "/readyz" })).toBe(true);
    expect(otelDoubles.httpOptions?.ignoreIncomingRequestHook({ url: "/api/v1/bookmarks" })).toBe(false);

    otelDoubles.shutdown.mockRejectedValueOnce(new Error("collector unavailable"));
    await expect(shutdownTelemetry()).resolves.toBeUndefined();
    expect(otelDoubles.shutdown).toHaveBeenCalledOnce();
  });

  it("honors an explicit OpenTelemetry service name", async () => {
    vi.stubEnv("OTEL_SERVICE_NAME", "stackverse-gateway-custom");
    otelDoubles.config.otelEnabled = true;

    await import("./otel.js");

    expect(otelDoubles.sdkOptions).toMatchObject({ serviceName: "stackverse-gateway-custom" });
  });
});
