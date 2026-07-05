<script lang="ts">
  import type { Snippet } from "svelte";

  interface Props {
    label: string;
    hint?: string;
    error?: string;
    children: Snippet;
  }

  let { label, hint = undefined, error = undefined, children }: Props = $props();
</script>

<label class={`sv-field${error ? " is-invalid" : ""}`}>
  <span class="sv-label">{label}</span>
  {@render children()}
  {#if hint}
    <span class="sv-field-hint">{hint}</span>
  {/if}
  {#if error}
    <span class="sv-field-error">{error}</span>
  {/if}
</label>
