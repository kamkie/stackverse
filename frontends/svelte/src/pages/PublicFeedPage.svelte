<script lang="ts">
  import { onMount } from "svelte";
  import { loadBookmarkCursor } from "../lib/bookmarkCursor";
  import { i18n, m } from "../lib/i18n";
  import { isReported } from "../lib/reportedStore";
  import { session } from "../lib/session";
  import type { Bookmark } from "../lib/types";
  import { fromStore } from "svelte/store";
  import BookmarkCard from "../components/BookmarkCard.svelte";
  import ReportDialog from "../components/ReportDialog.svelte";

  let { toast }: { toast: (message: string, tone?: "success" | "danger") => void } = $props();

  let bookmarks: Bookmark[] = $state([]);
  let nextCursor: string | undefined = $state(undefined);
  let loading = $state(true);
  let error: Error | null = $state(null);
  let q = $state("");
  let reporting: Bookmark | null = $state(null);
  const i18nState = fromStore(i18n);
  const sessionState = fromStore(session);

  async function load(reset = true) {
    loading = true;
    error = null;
    try {
      const loaded = await loadBookmarkCursor({
        reset,
        current: bookmarks,
        nextCursor,
        params: {
          visibility: "public",
          q,
        },
      });
      bookmarks = loaded.bookmarks;
      nextCursor = loaded.nextCursor;
    } catch (caught) {
      error = caught instanceof Error ? caught : new Error(String(caught));
    } finally {
      loading = false;
    }
  }

  onMount(() => {
    void load();
  });
</script>

<section class="sv-content">
  <h1 class="sv-page-title">{m(i18nState.current, "ui.nav.public-feed")}</h1>
  <div class="sv-toolbar">
    <input
      type="search"
      class="sv-input"
      placeholder={m(i18nState.current, "ui.bookmarks.search.placeholder")}
      bind:value={q}
      onchange={() => load()}
    />
  </div>

  {#if loading && bookmarks.length === 0}
    <div class="sv-loading"><span class="sv-spinner"></span></div>
  {:else if error}
    <div class="sv-alert sv-alert--danger" role="alert">{error.message}</div>
  {:else if bookmarks.length === 0}
    <div class="sv-empty">{m(i18nState.current, "ui.bookmarks.no-matches")}</div>
  {:else}
    <ul class="sv-card-list">
      {#each bookmarks as bookmark (bookmark.id)}
        <BookmarkCard
          {bookmark}
          mode="feed"
          reported={isReported(bookmark.id)}
          onReport={sessionState.current?.authenticated ? (item) => (reporting = item) : undefined}
        />
      {/each}
    </ul>
    {#if nextCursor}
      <div class="sv-load-more">
        <button type="button" class="sv-button" disabled={loading} onclick={() => load(false)}>
          {m(i18nState.current, "ui.action.load-more")}
        </button>
      </div>
    {/if}
  {/if}
</section>

{#if reporting}
  <ReportDialog
    bookmark={reporting}
    {toast}
    onDone={() => load()}
    onClose={() => (reporting = null)}
  />
{/if}
