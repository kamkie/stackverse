import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

type SignalHandler = () => void;

const serverDoubles = vi.hoisted(() => ({
  buildApp: vi.fn(),
  listen: vi.fn(),
  close: vi.fn(),
  shutdownTelemetry: vi.fn(),
  logEvent: vi.fn(),
  fatal: vi.fn(),
  handlers: new Map<string, SignalHandler>(),
  config: {
    port: 8000,
    backendUrl: new URL("http://backend.test"),
    frontendUrl: new URL("http://frontend.test"),
    spaRoot: "C:/stackverse/spa",
    redisUrl: "redis://user:secret@redis.test:6380/2",
    oidcIssuerUri: "https://idp.example/realms/stackverse",
    oidcInternalIssuerUri: "http://keycloak:8080/realms/stackverse",
    oidcClientId: "stackverse-gateway",
    oidcClientSecret: "client-secret",
    publicUrl: new URL("https://stackverse.example"),
    cookiesSecure: true,
    logLevel: "info",
    logFormat: "json",
    otelEnabled: true,
  },
}));

vi.mock("./app.js", () => ({ buildApp: serverDoubles.buildApp }));
vi.mock("./config.js", () => ({ config: serverDoubles.config }));
vi.mock("./logging.js", () => ({
  logEvent: serverDoubles.logEvent,
  logger: { fatal: serverDoubles.fatal },
}));
vi.mock("./otel.js", () => ({ shutdownTelemetry: serverDoubles.shutdownTelemetry }));

beforeEach(() => {
  vi.resetModules();
  vi.clearAllMocks();
  serverDoubles.handlers.clear();
  serverDoubles.config.redisUrl = "redis://user:secret@redis.test:6380/2";
  serverDoubles.listen.mockResolvedValue("https://127.0.0.1:8000");
  serverDoubles.close.mockResolvedValue(undefined);
  serverDoubles.shutdownTelemetry.mockResolvedValue(undefined);
  serverDoubles.buildApp.mockResolvedValue({ listen: serverDoubles.listen, close: serverDoubles.close });

  vi.spyOn(process, "on").mockImplementation(((event: string, listener: SignalHandler) => {
    serverDoubles.handlers.set(event, listener);
    return process;
  }) as typeof process.on);
  vi.spyOn(process, "exit").mockImplementation((() => undefined as never) as typeof process.exit);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("gateway process lifecycle", () => {
  it("starts on all interfaces, redacts Redis credentials, and shuts down exactly once", async () => {
    await import("./server.js");

    await vi.waitFor(() => {
      expect(serverDoubles.listen).toHaveBeenCalledWith({ port: 8000, host: "0.0.0.0" });
    });
    expect(serverDoubles.logEvent).toHaveBeenCalledWith(
      "info",
      "application_start",
      "success",
      "Stackverse gateway (node-fastify) listening on :8000",
      expect.objectContaining({
        redis_endpoint: "redis://redis.test:6380",
        backend_url: "http://backend.test/",
        frontend_url: "http://frontend.test/",
        public_url: "https://stackverse.example/",
        otel_sdk_disabled: false,
      }),
    );
    expect(JSON.stringify(serverDoubles.logEvent.mock.calls)).not.toContain("secret@redis");

    serverDoubles.handlers.get("SIGTERM")?.();
    await vi.waitFor(() => {
      expect(serverDoubles.close).toHaveBeenCalledOnce();
      expect(serverDoubles.shutdownTelemetry).toHaveBeenCalledOnce();
      expect(process.exit).toHaveBeenCalledWith(0);
    });
    expect(serverDoubles.logEvent).toHaveBeenCalledWith(
      "info",
      "application_stop",
      "success",
      "Received SIGTERM, shutting down",
      { signal: "SIGTERM" },
    );

    serverDoubles.handlers.get("SIGINT")?.();
    expect(serverDoubles.close).toHaveBeenCalledOnce();
    expect(serverDoubles.shutdownTelemetry).toHaveBeenCalledOnce();
  });

  it("filters key-value credentials from non-URL Redis endpoint lists", async () => {
    serverDoubles.config.redisUrl = "host=redis,password=secret,redis-a:6379,redis-b:6379";

    await import("./server.js");

    await vi.waitFor(() => {
      expect(serverDoubles.logEvent).toHaveBeenCalledWith(
        "info",
        "application_start",
        "success",
        expect.any(String),
        expect.objectContaining({ redis_endpoint: "redis-a:6379,redis-b:6379" }),
      );
    });
    expect(JSON.stringify(serverDoubles.logEvent.mock.calls)).not.toContain("password=secret");
  });

  it("logs a fatal startup error and exits non-zero", async () => {
    const startupError = new Error("listener refused to bind");
    serverDoubles.buildApp.mockRejectedValueOnce(startupError);

    await import("./server.js");

    await vi.waitFor(() => {
      expect(serverDoubles.fatal).toHaveBeenCalledWith({ err: startupError }, "Failed to start");
      expect(process.exit).toHaveBeenCalledWith(1);
    });
    expect(serverDoubles.listen).not.toHaveBeenCalled();
  });
});
