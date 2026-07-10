const MAX_FIELD_CHARS = 4000;
const BATCH_DELAY_MS = 120;

type Level = "log" | "info" | "warn" | "error" | "debug";

interface Entry {
  level: Level;
  message: string;
  time: string;
}

function sanitize(value: unknown): string {
  return (
    String(value)
      .slice(0, MAX_FIELD_CHARS)
      .replace(/\r?\n/g, "\\n")
      // eslint-disable-next-line no-control-regex
      .replace(/[\x00-\x08\x0b-\x1f\x7f]/g, "")
  );
}

function describe(value: unknown): string {
  if (value instanceof Error) return `${value.name}: ${value.message}`;
  if (typeof value === "string") return value;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

export function forwardConsoleToDevServer(): void {
  const original: Record<Level, (...args: unknown[]) => void> = {
    log: console.log.bind(console),
    info: console.info.bind(console),
    warn: console.warn.bind(console),
    error: console.error.bind(console),
    debug: console.debug.bind(console),
  };
  const queue: Entry[] = [];
  let timer: number | undefined;

  const flush = () => {
    timer = undefined;
    const entries = queue.splice(0);
    if (entries.length === 0) return;
    void fetch("/__client-log", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(entries),
      keepalive: true,
    }).catch(() => {
      // Dev-only diagnostics must never affect the app.
    });
  };

  const enqueue = (level: Level, args: unknown[]) => {
    queue.push({
      level,
      message: sanitize(args.map(describe).join(" ")),
      time: new Date().toISOString(),
    });
    timer ??= window.setTimeout(flush, BATCH_DELAY_MS);
  };

  for (const level of Object.keys(original) as Level[]) {
    console[level] = (...args: unknown[]) => {
      original[level](...args);
      enqueue(level, args);
    };
  }

  window.addEventListener("error", (event) => {
    enqueue("error", [event.error ?? event.message]);
  });
  window.addEventListener("unhandledrejection", (event) => {
    enqueue("error", [event.reason]);
  });
}
