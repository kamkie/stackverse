import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The dev server proxies /api and /auth to a real gateway. With mocks enabled
// (the default in dev; set VITE_API_MOCK=false to disable) the MSW service
// worker intercepts those requests before they ever reach the proxy.
const mockEnabled = process.env["VITE_API_MOCK"] !== "false";

export default defineConfig({
  plugins: [react()],
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
