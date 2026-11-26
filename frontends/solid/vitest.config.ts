import solid from "@solidjs/vite-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [solid()],
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    setupFiles: ["./src/test/setup.ts"],
    coverage: {
      reporter: ["text", "lcov"],
      reportsDirectory: "coverage",
      exclude: [
        ".pnp.cjs",
        ".yarn/**",
        "dist/**",
        "coverage/**",
        "src/test/**",
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
