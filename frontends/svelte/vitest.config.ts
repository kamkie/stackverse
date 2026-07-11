import { svelte } from "@sveltejs/vite-plugin-svelte";
import { svelteTesting } from "@testing-library/svelte/vite";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [svelte(), svelteTesting()],
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.ts"],
    coverage: {
      reporter: ["text", "lcov"],
      reportsDirectory: "coverage",
      include: ["src/**/*.{ts,svelte}"],
      exclude: [
        ".pnp.cjs",
        ".yarn/**",
        "dist/**",
        "coverage/**",
        "src/**/*.test.ts",
        "src/lib/types.ts",
        "src/test/**",
        "src/vite-env.d.ts",
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
