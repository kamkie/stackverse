import { defineConfig } from "cypress";

const stackverseUrl = process.env["STACKVERSE_URL"] ?? "http://localhost:8000";
const keycloakOrigin = process.env["KEYCLOAK_ORIGIN"] ?? "http://localhost:8180";

export default defineConfig({
  viewportWidth: 1280,
  viewportHeight: 800,
  defaultCommandTimeout: 10_000,
  requestTimeout: 10_000,
  responseTimeout: 30_000,
  video: true,
  screenshotOnRunFailure: true,
  screenshotsFolder: "cypress/screenshots",
  videosFolder: "cypress/videos",
  downloadsFolder: "cypress/downloads",
  retries: {
    runMode: process.env["CI"] ? 1 : 0,
    openMode: 0,
  },
  e2e: {
    baseUrl: stackverseUrl,
    specPattern: "cypress/e2e/**/*.cy.ts",
    supportFile: "cypress/support/e2e.ts",
    testIsolation: true,
    setupNodeEvents(_on, config) {
      config.env["KEYCLOAK_ORIGIN"] = config.env["KEYCLOAK_ORIGIN"] ?? keycloakOrigin;
      return config;
    },
  },
});
