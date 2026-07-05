<script lang="ts">
  import { i18n, m } from "../lib/i18n";
  import { fromStore } from "svelte/store";

  interface Props {
    page: number;
    totalPages: number;
    onPage: (page: number) => void;
  }

  let { page, totalPages, onPage }: Props = $props();
  const i18nState = fromStore(i18n);
</script>

{#if totalPages > 1}
  <div class="sv-pagination">
    <button
      type="button"
      class="sv-button sv-button--sm"
      disabled={page <= 0}
      onclick={() => onPage(page - 1)}
    >
      {m(i18nState.current, "ui.action.previous")}
    </button>
    <span>{page + 1} / {totalPages}</span>
    <button
      type="button"
      class="sv-button sv-button--sm"
      disabled={page >= totalPages - 1}
      onclick={() => onPage(page + 1)}
    >
      {m(i18nState.current, "ui.action.next")}
    </button>
  </div>
{/if}
