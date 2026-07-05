import { NodeSDK } from "@opentelemetry/sdk-node";
import { HttpInstrumentation } from "@opentelemetry/instrumentation-http";
import { PgInstrumentation } from "@opentelemetry/instrumentation-pg";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-grpc";
import { OTLPMetricExporter } from "@opentelemetry/exporter-metrics-otlp-grpc";
import { OTLPLogExporter } from "@opentelemetry/exporter-logs-otlp-grpc";
import { PeriodicExportingMetricReader } from "@opentelemetry/sdk-metrics";
import { BatchLogRecordProcessor } from "@opentelemetry/sdk-logs";
import { config } from "./config.js";

/**
 * Telemetry is silent by default (`OTEL_SDK_DISABLED=true`, docs/ARCHITECTURE.md).
 * When enabled, traces, metrics and log records export over OTLP/gRPC using the
 * standard `OTEL_*` variables (compose points them at the lgtm container).
 *
 * This module is imported first from `server.ts` so instrumentation registers
 * before `http`/`pg` are loaded. Log records reach the SDK through the pino
 * bridge in `logging.ts` — no monkey-patching of the logger.
 */
let sdk: NodeSDK | undefined;

if (config.otelEnabled) {
  sdk = new NodeSDK({
    serviceName: process.env["OTEL_SERVICE_NAME"] ?? "stackverse-backend-node-nestjs",
    instrumentations: [
      new HttpInstrumentation({
        // probe noise stays out of traces, as it stays out of access logs (docs/LOGGING.md §5)
        ignoreIncomingRequestHook: (request) =>
          request.url === "/healthz" || request.url === "/readyz",
      }),
      new PgInstrumentation(),
    ],
    traceExporter: new OTLPTraceExporter(),
    metricReader: new PeriodicExportingMetricReader({ exporter: new OTLPMetricExporter() }),
    logRecordProcessors: [new BatchLogRecordProcessor(new OTLPLogExporter())],
  });
  sdk.start();
}

export async function shutdownTelemetry(): Promise<void> {
  await sdk?.shutdown().catch(() => {
    // a dead telemetry pipeline must never block shutdown (docs/LOGGING.md §1)
  });
}
