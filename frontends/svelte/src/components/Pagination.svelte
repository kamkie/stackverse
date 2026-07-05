<script lang="ts">
  import { i18n, m } from "../lib/i18n";

  export let page: number;
  export let totalPages: number;
  export let onPage: (page: number) => void;
</script>

{#if totalPages > 1}
  <div class="sv-pagination">
    <button
      type="button"
      class="sv-button sv-button--sm"
      disabled={page <= 0}
      on:click={() => onPage(page - 1)}
    >
      {m($i18n, "ui.action.previous")}
    </button>
    <span>{page + 1} / {totalPages}</span>
    <button
      type="button"
      class="sv-button sv-button--sm"
      disabled={page >= totalPages - 1}
      on:click={() => onPage(page + 1)}
    >
      {m($i18n, "ui.action.next")}
    </button>
  </div>
{/if}
