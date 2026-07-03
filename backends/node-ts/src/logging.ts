import { pino, multistream, type Level, type StreamEntry } from "pino";
import pretty from "pino-pretty";
import { trace } from "@opentelemetry/api";
import { logs, SeverityNumber } from "@opentelemetry/api-logs";
import { config } from "./config.js";

/**
 * Structured JSON to stdout by default; `LOG_FORMAT=text` opts into
 * human-readable output for local dev (docs/LOGGING.md §2, §8). Service
 * identity is *not* stamped on every line — it travels as OpenTelemetry
 * resource attributes on exported records (§1), so `base` drops pino's
 * default pid/hostname fields.
 */
const OTEL_SEVERITY: Record<string, SeverityNumber> = {
  trace: SeverityNumber.TRACE,
  debug: SeverityNumber.DEBUG,
  info: SeverityNumber.INFO,
  warn: SeverityNumber.WARN,
  error: SeverityNumber.ERROR,
  fatal: SeverityNumber.FATAL,
};

/** Bridges every console line to the OTel Logs API (exported over OTLP next to traces). */
function otelLogStream(): { write: (line: string) => void } {
  return {
    write(line: string) {
      try {
        const record = JSON.parse(line) as Record<string, unknown> & {
          level?: string;
          msg?: string;
          time?: string;
        };
        const { level, msg, time, ...attributes } = record;
        logs.getLogger("stackverse-backend-node-ts").emit({
          severityNumber: OTEL_SEVERITY[level ?? "info"] ?? SeverityNumber.INFO,
          severityText: (level ?? "info").toUpperCase(),
          body: msg ?? "",
          timestamp: time ? new Date(time) : new Date(),
          attributes: Object.fromEntries(
            Object.entries(attributes).filter(([, value]) => value !== undefined && value !== null),
          ) as Record<string, string>,
        });
      } catch {
        // a logging failure must never crash or block the application (docs/LOGGING.md §1)
      }
    },
  };
}

const streams: StreamEntry[] = [
  { level: config.logLevel as Level, stream: config.logFormat === "text" ? pretty({ destination: 1 }) : process.stdout },
];
if (config.otelEnabled) {
  streams.push({ level: config.logLevel as Level, stream: otelLogStream() });
}

export const logger = pino(
  {
    level: config.logLevel,
    timestamp: pino.stdTimeFunctions.isoTime, // RFC 3339 UTC with millisecond precision (§2)
    formatters: { level: (label) => ({ level: label }) },
    base: null,
    // the link into Grafana is what makes a line actionable (§2): stamp the
    // active trace context on every console line when tracing is on
    mixin: () => {
      const context = trace.getActiveSpan()?.spanContext();
      return context ? { trace_id: context.traceId, span_id: context.spanId } : {};
    },
  },
  multistream(streams),
);

export type EventLevel = "debug" | "info" | "warn" | "error" | "fatal";
export type EventOutcome = "success" | "failure" | "denied" | "timeout";

/** One §5 contract event: stable `event` name + `outcome`, fields structured, message separate. */
export function logEvent(
  level: EventLevel,
  event: string,
  outcome: EventOutcome,
  message: string,
  fields: Record<string, unknown> = {},
): void {
  logger[level]({ event, outcome, ...fields }, message);
}
