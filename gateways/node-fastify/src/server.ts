// Telemetry first: instrumentation must register before http is imported.
import { shutdownTelemetry } from "./otel.js";
import { buildApp } from "./app.js";
import { config } from "./config.js";
import { logEvent, logger } from "./logging.js";

async function main(): Promise<void> {
  const app = await buildApp({ config });
  await app.listen({ port: config.port, host: "0.0.0.0" });

  logEvent("info", "application_start", "success", `Stackverse gateway (node-fastify) listening on :${config.port}`, {
    port: config.port,
    backend_url: config.backendUrl.toString(),
    frontend_url: config.frontendUrl?.toString(),
    public_url: config.publicUrl.toString(),
    redis_endpoint: redactedRedisEndpoint(config.redisUrl),
    oidc_issuer_uri: config.oidcIssuerUri,
    oidc_internal_issuer_uri: config.oidcInternalIssuerUri,
    oidc_client_id: config.oidcClientId,
    log_level: config.logLevel,
    log_format: config.logFormat,
    otel_sdk_disabled: !config.otelEnabled,
  });

  let stopping = false;
  const stop = async (signal: string): Promise<void> => {
    if (stopping) return;
    stopping = true;
    logEvent("info", "application_stop", "success", `Received ${signal}, shutting down`, { signal });
    await app.close();
    await shutdownTelemetry();
    process.exit(0);
  };
  process.on("SIGTERM", () => void stop("SIGTERM"));
  process.on("SIGINT", () => void stop("SIGINT"));
}

function redactedRedisEndpoint(redisUrl: string): string {
  try {
    const parsed = new URL(redisUrl);
    return `${parsed.protocol}//${parsed.hostname}${parsed.port ? `:${parsed.port}` : ""}`;
  } catch {
    return redisUrl
      .split(",")
      .filter((part) => !part.includes("="))
      .join(",");
  }
}

main().catch((error: unknown) => {
  logger.fatal({ err: error }, "Failed to start");
  process.exit(1);
});
