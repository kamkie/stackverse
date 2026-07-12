import solid from "@astrojs/solid-js";
import { defineConfig } from "astro/config";
import type { Plugin } from "vite";

const FORWARDED_LEVELS = new Set(["log", "info", "warn", "error", "debug"]);
const MAX_FIELD_CHARS = 4096;

function sanitizeLogField(value: unknown): string {
  return String(value)
    .slice(0, MAX_FIELD_CHARS)
    .replace(/\r?\n/g, "\\n")
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
                          const level = FORWARDED_LEVELS.has(entry.level)
                            ? entry.level
                            : "log";
                          console.log(
                            `[browser] ${sanitizeLogField(entry.time)} ${level.toUpperCase().padEnd(5)} ${sanitizeLogField(entry.message)}`,
                          );
                        }
                      } catch {
                        // Malformed browser batches are deliberately dropped.
                      }
                      res.statusCode = 204;
                      res.end();
                    });
      });
    },
  };
}

export default defineConfig({
  output: "static",
  build: { inlineStylesheets: "never" },
  devToolbar: { enabled: false },
  integrations: [solid()],
  vite: {
    plugins: [clientLogForwarder()],
    build: { assetsInlineLimit: 0 },
    server: {
      host: "127.0.0.1",
      port: 5173,
      proxy: {
        "/api": "http://localhost:8000",
        "/auth": "http://localhost:8000",
      },
      fs: { allow: ["../.."] },
    },
  },
});
