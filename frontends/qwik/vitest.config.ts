import { qwikVite } from "@builder.io/qwik/optimizer";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [qwikVite({ csr: true })],
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    coverage: {
      reporter: ["text", "lcov"],
      reportsDirectory: "coverage",
      exclude: [
        ".pnp.cjs",
        ".yarn/**",
        "dist/**",
        "coverage/**",
        "test-results/**",
        "vite.config.ts",
        "vitest.config.ts",
      ],
    },
    outputFile: {
      junit: "test-results/junit.xml",
    },
    reporters: ["default", "junit"],
  },
});
