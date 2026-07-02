import { defineConfig } from "@playwright/test";

// Contract conformance suite, run DIRECTLY against a backend (no gateway, no
// browser). Tokens come from Keycloak via the dev realm's password-grant
// client, so only the compose infra and one backend need to be running.
// BACKEND_URL / KEYCLOAK_URL override the dev-mode defaults.
const baseURL = process.env["BACKEND_URL"] ?? "http://localhost:8080";

export default defineConfig({
  testDir: "./tests",
  // specs mutate shared backend state (moderation, blocking) — serial
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env["CI"],
  reporter: [["list"], ["html", { open: "never" }]],
  timeout: 30_000,
  use: {
    baseURL,
  },
});
