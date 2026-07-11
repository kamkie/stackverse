import path from "node:path";
import { describe, expect, it } from "vitest";
import { loadConfig } from "./config.js";

describe("gateway environment configuration", () => {
  it("loads contract defaults from an empty environment", () => {
    const config = loadConfig({});

    expect(config).toMatchObject({
      port: 8000,
      redisUrl: "redis://localhost:6379",
      oidcIssuerUri: "http://localhost:8180/realms/stackverse",
      oidcClientId: "stackverse-gateway",
      cookiesSecure: false,
      logLevel: "info",
      logFormat: "json",
      otelEnabled: false,
    });
    expect(config.backendUrl.toString()).toBe("http://localhost:8080/");
    expect(config.publicUrl.toString()).toBe("http://localhost:8000/");
    expect(config.frontendUrl).toBeUndefined();
    expect(config.oidcInternalIssuerUri).toBeUndefined();
    expect(path.basename(config.spaRoot)).toBe("static");
  });

  it("trims optional values and derives HTTPS, logging, and telemetry modes", () => {
    const config = loadConfig({
      PORT: " 8443 ",
      BACKEND_URL: " https://backend.example/base ",
      FRONTEND_URL: " https://frontend.example/app ",
      SPA_ROOT: " C:/stackverse/spa ",
      REDIS_URL: " redis://redis.example:6380 ",
      OIDC_ISSUER_URI: " https://idp.example/realms/stackverse/// ",
      OIDC_INTERNAL_ISSUER_URI: " http://keycloak:8080/realms/stackverse// ",
      OIDC_CLIENT_ID: " custom-client ",
      OIDC_CLIENT_SECRET: " custom-secret ",
      PUBLIC_URL: " https://stackverse.example/base ",
      LOG_LEVEL: " DEBUG ",
      LOG_FORMAT: " TEXT ",
      OTEL_SDK_DISABLED: " FALSE ",
    });

    expect(config.port).toBe(8443);
    expect(config.backendUrl.toString()).toBe("https://backend.example/base");
    expect(config.frontendUrl?.toString()).toBe("https://frontend.example/app");
    expect(config.spaRoot).toBe("C:/stackverse/spa");
    expect(config.oidcIssuerUri).toBe("https://idp.example/realms/stackverse");
    expect(config.oidcInternalIssuerUri).toBe("http://keycloak:8080/realms/stackverse");
    expect(config.cookiesSecure).toBe(true);
    expect(config.logLevel).toBe("debug");
    expect(config.logFormat).toBe("text");
    expect(config.otelEnabled).toBe(true);
  });

  it("rejects a non-integer listener port before startup", () => {
    expect(() => loadConfig({ PORT: "8000.5" })).toThrow("PORT must be an integer");
    expect(() => loadConfig({ PORT: "not-a-number" })).toThrow("PORT must be an integer");
  });
});
