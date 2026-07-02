import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vite";

// The dev server proxies /api and /auth to a real gateway. With mocks enabled
// (the default in dev; set VITE_API_MOCK=false to disable) the MSW service
// worker intercepts those requests before they ever reach the proxy.
const mockEnabled = process.env["VITE_API_MOCK"] !== "false";

// Receives batched console output from the browser (posted by
// src/dev/forwardConsoleToDevServer.ts) and prints it to the dev server's
// stdout, so browser logs land in the terminal and dev-server.log.
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
        let body = "";
        req.on("data", (chunk: Buffer) => (body += chunk));
        req.on("end", () => {
          try {
            const entries = JSON.parse(body) as {
              level: string;
              message: string;
              time: string;
            }[];
            for (const { level, message, time } of entries) {
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
