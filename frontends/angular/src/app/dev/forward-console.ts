// Dev-only: mirrors browser console output and uncaught errors to the dev
// server (see the client-log middleware in proxy.conf.mjs), so everything the
// browser prints also lands in the terminal / .logs/frontend.log.

type Level = 'log' | 'info' | 'warn' | 'error' | 'debug';

interface LogEntry {
  level: Level;
  message: string;
  time: string;
}

const ENDPOINT = '/__client-log';
const FLUSH_INTERVAL_MS = 500;
const MAX_MESSAGE_LENGTH = 4000; // keep verbose dumps from flooding the log
const LEVELS: readonly Level[] = ['log', 'info', 'warn', 'error', 'debug'];

const queue: LogEntry[] = [];
let flushTimer: number | undefined;

function serialize(args: unknown[]): string {
  return args
    .map((arg) => {
      if (typeof arg === 'string') return arg;
      if (arg instanceof Error) return arg.stack ?? String(arg);
      try {
        return JSON.stringify(arg);
      } catch {
        return String(arg);
      }
    })
    .join(' ');
}

function enqueue(level: Level, args: unknown[]) {
  let message = serialize(args);
  if (message.length > MAX_MESSAGE_LENGTH) {
    message = `${message.slice(0, MAX_MESSAGE_LENGTH)}… [truncated ${message.length - MAX_MESSAGE_LENGTH} chars]`;
  }
  queue.push({ level, message, time: new Date().toISOString() });
  flushTimer ??= window.setTimeout(flush, FLUSH_INTERVAL_MS);
}

function flush() {
  flushTimer = undefined;
  if (queue.length === 0) return;
  const batch = queue.splice(0);
  // keepalive lets the final batch survive page unloads; failures are
  // swallowed — log forwarding must never break or re-log into the app.
  void fetch(ENDPOINT, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(batch),
    keepalive: true,
  }).catch(() => {});
}

export function forwardConsoleToDevServer() {
  for (const level of LEVELS) {
    const original = console[level].bind(console);
    console[level] = (...args: unknown[]) => {
      original(...args);
      enqueue(level, args);
    };
  }
  window.addEventListener('error', (event) => {
    enqueue('error', [
      `Uncaught: ${event.message}`,
      `${event.filename}:${event.lineno}:${event.colno}`,
    ]);
  });
  window.addEventListener('unhandledrejection', (event) => {
    enqueue('error', ['Unhandled rejection:', event.reason]);
  });
  window.addEventListener('pagehide', flush);
}
