import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: "jsdom",
    setupFiles: ["src/test/setup.ts"],
    restoreMocks: true,
    reporters: process.env.CI
      ? ["default", ["junit", { outputFile: "test-results/junit.xml" }]]
      : ["default"],
    coverage: {
      provider: "v8",
      reporter: ["text", "lcov"],
      include: ["src/**"],
      exclude: ["src/api/schema.ts", "src/test/**"],
    },
  },
});
