import sveltePlugin from "prettier-plugin-svelte";

export default {
  plugins: [sveltePlugin],
  overrides: [{ files: "*.svelte", options: { parser: "svelte" } }],
};
