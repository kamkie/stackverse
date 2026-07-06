import { defineConfig, type Plugin } from "vite";

// Dev-server wiring: /api and /auth proxy to a real gateway on :8000, while
// browser console/action logs are mirrored to the dev server via /__client-log.
const FORWARDED_LEVELS = new Set(["log", "info", "warn", "error", "debug"]);
const MAX_BODY_BYTES = 256 * 1024;
const MAX_FIELD_CHARS = 4096;

function sanitizeLogField(value: unknown): string {
  return String(value)
    .slice(0, MAX_FIELD_CHARS)
    .replace(/\r?\n/g, "\\n")
    // eslint-disable-next-line no-control-regex
    .replace(/[\x00-\x08\x0b-\x1f\x7f]/g, "");
}

function clientLogForwarder(): Plugin {
  return {
    name: "stackverse:client-log-forwarder",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use("/__client-log", (req, res) => {
        if (req.method !== "POST") {
          res.statusCode = 405;
          res.end();
          return;
        }

        let received = 0;
        let body = "";
        req.on("data", (chunk: Buffer) => {
          received += chunk.length;
          if (received > MAX_BODY_BYTES) {
            res.statusCode = 413;
            res.end();
            req.destroy();
            return;
          }
          body += chunk;
        });
        req.on("end", () => {
          if (res.writableEnded) return;
          try {
            const entries = JSON.parse(body) as {
              level: string;
              message: string;
              time: string;
            }[];
            for (const entry of entries) {
              const level = FORWARDED_LEVELS.has(entry.level) ? entry.level : "log";
              const time = sanitizeLogField(entry.time);
              const message = sanitizeLogField(entry.message);
              console.log(`[browser] ${time} ${level.toUpperCase().padEnd(5)} ${message}`);
            }
          } catch {
            // Malformed batches are dropped; logging must never crash the server.
          }
          res.statusCode = 204;
          res.end();
        });
      });
    },
  };
}

export default defineConfig({
  plugins: [clientLogForwarder()],
  server: {
    proxy: {
      "/api": { target: "http://localhost:8000" },
      "/auth": { target: "http://localhost:8000" },
    },
    fs: {
      allow: ["../.."],
    },
  },
});
