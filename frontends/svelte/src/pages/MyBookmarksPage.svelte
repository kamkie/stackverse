<script lang="ts">
  import { onMount } from "svelte";
  import { api } from "../lib/api";
  import { loadBookmarkCursor } from "../lib/bookmarkCursor";
  import { i18n, m } from "../lib/i18n";
  import type { Bookmark } from "../lib/types";
  import BookmarkCard from "../components/BookmarkCard.svelte";
  import BookmarkFormDialog from "../components/BookmarkFormDialog.svelte";
  import ConfirmDialog from "../components/ConfirmDialog.svelte";
  import TagSidebar from "../components/TagSidebar.svelte";
  import { fromStore } from "svelte/store";

  let { toast }: { toast: (message: string, tone?: "success" | "danger") => void } = $props();

  let bookmarks: Bookmark[] = $state([]);
  let nextCursor: string | undefined = $state(undefined);
  let loading = $state(true);
  let error: Error | null = $state(null);
  let q = $state("");
  let selectedTag = $state("");
  let dialog: { mode: "create" } | { mode: "edit"; bookmark: Bookmark } | null = $state(null);
  let deleting: Bookmark | null = $state(null);
  let tagSidebar: TagSidebar | undefined = $state();
  const i18nState = fromStore(i18n);

  async function load(reset = true) {
    loading = true;
    error = null;
    try {
      const loaded = await loadBookmarkCursor({
        reset,
        current: bookmarks,
        nextCursor,
        params: {
          q,
          tag: selectedTag ? [selectedTag] : [],
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

  async function remove(bookmark: Bookmark) {
    try {
      await api<void>(`/api/v1/bookmarks/${bookmark.id}`, { method: "DELETE" });
      toast(m(i18nState.current, "ui.toast.bookmark-deleted"));
      deleting = null;
      await load();
      await tagSidebar?.reload();
    } catch (caught) {
      toast(caught instanceof Error ? caught.message : String(caught), "danger");
    }
  }

  function selectTag(tag: string) {
    selectedTag = tag;
    void load();
  }

  onMount(() => {
    void load();
  });
</script>

<div class="sv-layout">
  <TagSidebar bind:this={tagSidebar} selected={selectedTag} onSelect={selectTag} />
  <section class="sv-content">
    <h1 class="sv-page-title">{m(i18nState.current, "ui.nav.my-bookmarks")}</h1>
    <div class="sv-toolbar">
      <input
        type="search"
        class="sv-input"
        placeholder={m(i18nState.current, "ui.bookmarks.search.placeholder")}
        bind:value={q}
        onchange={() => load()}
      />
      <button type="button" class="sv-button sv-button--primary" onclick={() => (dialog = { mode: "create" })}>
        {m(i18nState.current, "ui.action.add")}
      </button>
    </div>

    {#if loading && bookmarks.length === 0}
      <div class="sv-loading"><span class="sv-spinner"></span></div>
    {:else if error}
      <div class="sv-alert sv-alert--danger" role="alert">{error.message}</div>
    {:else if bookmarks.length === 0}
      <div class="sv-empty">
        {q || selectedTag ? m(i18nState.current, "ui.bookmarks.no-matches") : m(i18nState.current, "ui.bookmarks.empty")}
      </div>
    {:else}
      <ul class="sv-card-list">
        {#each bookmarks as bookmark (bookmark.id)}
          <BookmarkCard
            {bookmark}
            mode="own"
            onEdit={(item) => (dialog = { mode: "edit", bookmark: item })}
            onDelete={(item) => (deleting = item)}
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
</div>

{#if dialog}
  <BookmarkFormDialog
    bookmark={dialog.mode === "edit" ? dialog.bookmark : undefined}
    onSaved={async () => {
      await load();
      await tagSidebar?.reload();
    }}
    onClose={() => (dialog = null)}
  />
{/if}

{#if deleting}
  <ConfirmDialog
    title={`${m(i18nState.current, "ui.action.delete")} - ${deleting.title}`}
    body={m(i18nState.current, "ui.confirm.delete-bookmark")}
    ctx={`bookmark:${deleting.id}`}
    confirmLabel={m(i18nState.current, "ui.action.delete")}
    cancelLabel={m(i18nState.current, "ui.action.cancel")}
    onConfirm={() => remove(deleting as Bookmark)}
    onClose={() => (deleting = null)}
  />
{/if}
