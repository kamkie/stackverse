import js from "@eslint/js";
import eslintConfigPrettier from "eslint-config-prettier";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["coverage/**", "dist/**", "test-results/**"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  eslintConfigPrettier,
  {
    rules: {
      "no-empty": ["error", { allowEmptyCatch: true }],
      "no-undef": "off",
    },
  },
);
