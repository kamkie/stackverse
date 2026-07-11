import { pino, multistream, type Level, type StreamEntry } from "pino";
import pretty from "pino-pretty";
import { trace } from "@opentelemetry/api";
import { logs, SeverityNumber } from "@opentelemetry/api-logs";
import { config } from "./config.js";

const OTEL_SEVERITY: Record<string, SeverityNumber> = {
  trace: SeverityNumber.TRACE,
  debug: SeverityNumber.DEBUG,
  info: SeverityNumber.INFO,
  warn: SeverityNumber.WARN,
  error: SeverityNumber.ERROR,
  fatal: SeverityNumber.FATAL,
};

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
        logs.getLogger("stackverse-gateway-node-fastify").emit({
          severityNumber: OTEL_SEVERITY[level ?? "info"] ?? SeverityNumber.INFO,
          severityText: (level ?? "info").toUpperCase(),
          body: msg ?? "",
          timestamp: time ? new Date(time) : new Date(),
          attributes: Object.fromEntries(
            Object.entries(attributes).filter(([, value]) => value !== undefined && value !== null),
          ) as Record<string, string>,
        });
      } catch {
        // Logging failures must never crash or block the gateway.
      }
    },
  };
}

const streams: StreamEntry[] = [
  {
    level: config.logLevel as Level,
    stream: config.logFormat === "text" ? pretty({ destination: 1 }) : process.stdout,
  },
];
if (config.otelEnabled) {
  streams.push({ level: config.logLevel as Level, stream: otelLogStream() });
}

export const logger = pino(
  {
    level: config.logLevel,
    timestamp: pino.stdTimeFunctions.isoTime,
    formatters: { level: (label) => ({ level: label }) },
    base: null,
    mixin: () => {
      const context = trace.getActiveSpan()?.spanContext();
      return context ? { trace_id: context.traceId, span_id: context.spanId } : {};
    },
  },
  multistream(streams),
);

export type EventLevel = "debug" | "info" | "warn" | "error" | "fatal";
export type EventOutcome = "success" | "failure" | "denied" | "timeout";

export function sanitizeLogValue(value: string | undefined, maxLength = 200): string | undefined {
  if (value === undefined) return undefined;
  const normalized = value.replace(/\r\n/g, "\n");
  let result = "";
  for (const ch of normalized) {
    if (result.length >= maxLength) {
      result += "...";
      break;
    }
    if (ch === "\n" || ch === "\r") {
      result += "\\n";
    } else if (ch >= " ") {
      result += ch;
    }
  }
  return result;
}

export function logEvent(
  level: EventLevel,
  event: string,
  outcome: EventOutcome,
  message: string,
  fields: Record<string, unknown> = {},
): void {
  logger[level]({ event, outcome, ...fields }, message);
}
