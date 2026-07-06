import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "jsdom",
    globals: true,
    coverage: {
      reporter: ["text", "lcov"],
      reportsDirectory: "coverage",
      exclude: [
        ".pnp.cjs",
        ".yarn/**",
        "coverage/**",
        "dist/**",
        "src/main.ts",
        "test-results/**",
        "vite.config.ts",
        "vitest.config.ts",
      ],
    },
    reporters: ["default", "junit"],
    outputFile: {
      junit: "test-results/junit.xml",
    },
  },
});
