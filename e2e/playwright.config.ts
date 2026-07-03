import { defineConfig, devices } from "@playwright/test";

// Black-box suite against a running stack (any backend/gateway/frontend combo
// satisfying the contract). Start one first — scripts/dev-stack.ps1|.sh for dev
// mode or scripts/run-stack.ps1|.sh for containers — then `yarn test` here.
const baseURL = process.env["STACKVERSE_URL"] ?? "http://localhost:8000";

export default defineConfig({
  testDir: "./tests",
  // specs mutate shared backend state (reports, blocking, messages) — serial
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env["CI"],
  // CI also emits JUnit XML for Codecov test analytics
  reporter: process.env["CI"]
    ? [["list"], ["html", { open: "never" }], ["junit", { outputFile: "test-results/junit.xml" }]]
    : [["list"], ["html", { open: "never" }]],
  timeout: 30_000,
  use: {
    baseURL,
    trace: "retain-on-failure",
  },
  projects: [
    { name: "setup", testMatch: /auth\.setup\.ts/ },
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
      dependencies: ["setup"],
    },
  ],
});
