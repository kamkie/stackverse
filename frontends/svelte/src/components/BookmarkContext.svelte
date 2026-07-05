<script lang="ts">
  import { onMount } from "svelte";
  import { api } from "../lib/api";
  import { i18n, m } from "../lib/i18n";
  import type { Bookmark } from "../lib/types";
  import { fromStore } from "svelte/store";

  let { bookmarkId }: { bookmarkId: string } = $props();

  let bookmark: Bookmark | null = $state(null);
  let failed = $state(false);
  const i18nState = fromStore(i18n);

  onMount(() => {
    let cancelled = false;
    api<Bookmark>(`/api/v1/bookmarks/${bookmarkId}`)
      .then((loaded) => {
        if (!cancelled) bookmark = loaded;
      })
      .catch(() => {
        if (!cancelled) failed = true;
      });
    return () => {
      cancelled = true;
    };
  });
</script>

{#if bookmark}
  <strong>{bookmark.title}</strong>
  <div>
    <a class="sv-bookmark-url" href={bookmark.url} target="_blank" rel="noreferrer">
      {bookmark.url}
    </a>
  </div>
{:else}
  <span class="sv-cell-mono">{bookmarkId}</span>
  {#if failed}
    <div class="sv-field-hint">{m(i18nState.current, "ui.reports.bookmark-unavailable")}</div>
  {/if}
{/if}
