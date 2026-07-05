<script lang="ts">
  export let label: string;
  export let hint: string | undefined = undefined;
  export let error: string | undefined = undefined;
</script>

<label class={`sv-field${error ? " is-invalid" : ""}`}>
  <span class="sv-label">{label}</span>
  <slot />
  {#if hint}
    <span class="sv-field-hint">{hint}</span>
  {/if}
  {#if error}
    <span class="sv-field-error">{error}</span>
  {/if}
</label>
