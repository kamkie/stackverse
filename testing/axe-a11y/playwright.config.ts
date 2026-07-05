import { defineConfig, devices } from "@playwright/test";

// Optional axe-core showcase against a running composed stack. Start one first
// with scripts/run-stack.* or scripts/dev-stack.*, then run `yarn test` here.
const baseURL = process.env["STACKVERSE_URL"] ?? "http://localhost:8000";

export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env["CI"],
  reporter: process.env["CI"]
    ? [["list"], ["html", { open: "never" }], ["junit", { outputFile: "test-results/junit.xml" }]]
    : [["list"], ["html", { open: "never" }]],
  timeout: 45_000,
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
