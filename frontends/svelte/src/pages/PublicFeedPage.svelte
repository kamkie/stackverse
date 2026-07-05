<script lang="ts">
  import { onMount } from "svelte";
  import { loadBookmarkCursor } from "../lib/bookmarkCursor";
  import { i18n, m } from "../lib/i18n";
  import { isReported } from "../lib/reportedStore";
  import { session } from "../lib/session";
  import type { Bookmark } from "../lib/types";
  import BookmarkCard from "../components/BookmarkCard.svelte";
  import ReportDialog from "../components/ReportDialog.svelte";

  export let toast: (message: string, tone?: "success" | "danger") => void;

  let bookmarks: Bookmark[] = [];
  let nextCursor: string | undefined = undefined;
  let loading = true;
  let error: Error | null = null;
  let q = "";
  let reporting: Bookmark | null = null;

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
  <h1 class="sv-page-title">{m($i18n, "ui.nav.public-feed")}</h1>
  <div class="sv-toolbar">
    <input
      type="search"
      class="sv-input"
      placeholder={m($i18n, "ui.bookmarks.search.placeholder")}
      bind:value={q}
      on:change={() => load()}
    />
  </div>

  {#if loading && bookmarks.length === 0}
    <div class="sv-loading"><span class="sv-spinner"></span></div>
  {:else if error}
    <div class="sv-alert sv-alert--danger" role="alert">{error.message}</div>
  {:else if bookmarks.length === 0}
    <div class="sv-empty">{m($i18n, "ui.bookmarks.no-matches")}</div>
  {:else}
    <ul class="sv-card-list">
      {#each bookmarks as bookmark (bookmark.id)}
        <BookmarkCard
          {bookmark}
          mode="feed"
          reported={isReported(bookmark.id)}
          onReport={$session?.authenticated ? (item) => (reporting = item) : undefined}
        />
      {/each}
    </ul>
    {#if nextCursor}
      <div class="sv-load-more">
        <button type="button" class="sv-button" disabled={loading} on:click={() => load(false)}>
          {m($i18n, "ui.action.load-more")}
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
