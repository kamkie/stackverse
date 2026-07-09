import eslint from "@eslint/js";
import qwik from "eslint-plugin-qwik";
import tseslint from "typescript-eslint";

export default tseslint.config(
  {
    ignores: [
      ".pnp.cjs",
      ".yarn/**",
      "coverage/**",
      "dist/**",
      "public/**",
      "test-results/**",
    ],
  },
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["**/*.{ts,tsx}"],
    plugins: { qwik },
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      ...qwik.configs.recommended.rules,
      "@typescript-eslint/no-explicit-any": "off",
    },
  },
);
