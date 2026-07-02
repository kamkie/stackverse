import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vite";

// The dev server proxies /api and /auth to a real gateway. With mocks enabled
// (the default in dev; set VITE_API_MOCK=false to disable) the MSW service
// worker intercepts those requests before they ever reach the proxy.
const mockEnabled = process.env["VITE_API_MOCK"] !== "false";

// Receives batched console output from the browser (posted by
// src/dev/forwardConsoleToDevServer.ts) and prints it to the dev server's
// stdout, so browser logs land in the terminal and dev-server.log.
const FORWARDED_LEVELS = new Set(["log", "info", "warn", "error", "debug"]);

// Everything in the batch is client-controlled: strip control characters and
// encode newlines so a console.log (or crafted POST) can't forge log lines.
function sanitizeLogField(value: unknown): string {
  return String(value)
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
        const MAX_BODY_BYTES = 256 * 1024;
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
            // malformed batch — drop it, never crash the dev server
          }
          res.statusCode = 204;
          res.end();
        });
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), clientLogForwarder()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8000",
      "/auth": {
        target: "http://localhost:8000",
        // "Log in" is a full-page navigation, which service workers never
        // intercept — in mock mode serve the SPA instead and let the mock
        // bootstrap (src/mocks/browser.ts) complete the fake OIDC dance.
        bypass: (req) =>
          mockEnabled && req.url?.includes("/auth/login")
            ? "/index.html"
            : undefined,
      },
    },
    fs: {
      // spec/design and spec/messages live at the repo root, outside this app.
      allow: ["../.."],
    },
  },
});
