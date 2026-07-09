import eslint from "@eslint/js";
import lit from "eslint-plugin-lit";
import wc from "eslint-plugin-wc";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  {
    ignores: [
      ".pnp.*",
      ".yarn/**",
      "coverage/**",
      "dist/**",
      "public/**",
      "test-results/**",
    ],
  },
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  lit.configs["flat/recommended"],
  wc.configs["flat/recommended"],
  {
    files: ["src/**/*.ts"],
    languageOptions: {
      globals: globals.browser,
    },
  },
  {
    files: ["*.config.{js,ts}", "vite.config.ts", "vitest.config.ts"],
    languageOptions: {
      globals: globals.node,
    },
  },
);
