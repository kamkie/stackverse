// Telemetry first: instrumentation must register before http/pg are imported.
import { shutdownTelemetry } from "./otel.js";
import { config } from "./config.js";
import { logger, logEvent } from "./logging.js";
import { pool, runMigrations } from "./db.js";
import { seedMessages } from "./seed.js";
import { buildApp } from "./app.js";

async function main(): Promise<void> {
  await runMigrations();
  await seedMessages();

  const app = buildApp();
  await app.listen({ port: config.port, host: "0.0.0.0" });

  // effective config with secrets redacted (docs/LOGGING.md §5)
  logEvent("info", "application_start", "success", `Stackverse backend (node-ts) listening on :${config.port}`, {
    port: config.port,
    db_host: config.db.host,
    db_port: config.db.port,
    db_name: config.db.database,
    oidc_issuer: config.oidc.issuerUri,
    oidc_jwks_uri: config.oidc.jwksUri ?? "(via OIDC discovery)",
    seed_messages_dir: config.seedMessagesDir,
    log_level: config.logLevel,
    log_format: config.logFormat,
    otel_enabled: config.otelEnabled,
  });

  let stopping = false;
  const stop = async (signal: string): Promise<void> => {
    if (stopping) return;
    stopping = true;
    logEvent("info", "application_stop", "success", `Received ${signal}, shutting down`, { signal });
    await app.close();
    await pool.end();
    await shutdownTelemetry();
    process.exit(0);
  };
  process.on("SIGTERM", () => void stop("SIGTERM"));
  process.on("SIGINT", () => void stop("SIGINT"));
}

main().catch((error: unknown) => {
  // the process cannot continue; logged once, exit non-zero (docs/LOGGING.md §3)
  logger.fatal({ err: error }, "Failed to start");
  process.exit(1);
});
