// Dev-server wiring: /api and /auth proxy to a real gateway on :8000, and the
// browser's console output (posted by src/app/dev/forward-console.ts to
// /__client-log) lands on the dev server's stdout, so browser logs show up in
// the terminal and .logs/frontend.log.
//
// The Angular CLI dev server has no middleware hook, so the client-log sink
// runs as a tiny loopback HTTP server started by this module (proxy configs
// are ES modules evaluated inside the dev-server process — its stdout is the
// terminal we want) and /__client-log proxies to it.
import { createServer } from 'node:http';

const FORWARDED_LEVELS = new Set(['log', 'info', 'warn', 'error', 'debug']);
const MAX_BODY_BYTES = 256 * 1024;
// The browser-side forwarder already truncates at 4000 chars; this server-side
// cap only matters for crafted POSTs that bypass it (docs/LOGGING.md §6:
// client-controlled input gets its length capped where it is logged).
const MAX_FIELD_CHARS = 4096;

// Everything in the batch is client-controlled: strip control characters,
// encode newlines, and cap length so a console.log (or crafted POST) can't
// forge or flood log lines (docs/LOGGING.md §6).
function sanitizeLogField(value) {
  return (
    String(value)
      .slice(0, MAX_FIELD_CHARS)
      .replace(/\r?\n/g, '\\n')
      // eslint-disable-next-line no-control-regex
      .replace(/[\x00-\x08\x0b-\x1f\x7f]/g, '')
  );
}

const sink = createServer((req, res) => {
  if (req.method !== 'POST') {
    res.statusCode = 405;
    res.end();
    return;
  }
  let received = 0;
  let body = '';
  req.on('data', (chunk) => {
    received += chunk.length;
    if (received > MAX_BODY_BYTES) {
      res.statusCode = 413;
      res.end();
      req.destroy();
      return;
    }
    body += chunk;
  });
  req.on('end', () => {
    if (res.writableEnded) return;
    try {
      const entries = JSON.parse(body);
      for (const entry of entries) {
        const level = FORWARDED_LEVELS.has(entry.level) ? entry.level : 'log';
        const time = sanitizeLogField(entry.time);
        const message = sanitizeLogField(entry.message);
        console.log(`[browser] ${time} ${level.toUpperCase().padEnd(5)} ${message}`);
      }
    } catch {
      // malformed batch — drop it, never crash the dev server
    }
    res.statusCode = 204;
    res.end();
  });
});
sink.unref(); // never keep the dev server process alive on its own

const sinkPort = await new Promise((resolve, reject) => {
  sink.once('error', reject);
  sink.listen(0, '127.0.0.1', () => resolve(sink.address().port));
});

export default {
  '/api': { target: 'http://localhost:8000' },
  '/auth': { target: 'http://localhost:8000' },
  '/__client-log': { target: `http://127.0.0.1:${sinkPort}` },
};
