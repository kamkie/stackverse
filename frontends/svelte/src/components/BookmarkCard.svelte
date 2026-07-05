<script lang="ts">
  import type { Bookmark } from "../lib/types";
  import { i18n, m } from "../lib/i18n";
  import { fromStore } from "svelte/store";

  interface Props {
    bookmark: Bookmark;
    mode: "own" | "feed";
    reported?: boolean;
    onEdit?: (bookmark: Bookmark) => void;
    onDelete?: (bookmark: Bookmark) => void;
    onReport?: (bookmark: Bookmark) => void;
  }

  let {
    bookmark,
    mode,
    reported = false,
    onEdit = undefined,
    onDelete = undefined,
    onReport = undefined,
  }: Props = $props();
  const i18nState = fromStore(i18n);
</script>

<li class="sv-card sv-bookmark" data-ctx={`bookmark:${bookmark.id}`}>
  <div class="sv-bookmark-head">
    <h2 class="sv-bookmark-title">
      <a href={bookmark.url} target="_blank" rel="noreferrer">{bookmark.title}</a>
    </h2>
    {#if bookmark.status === "hidden"}
      <span class="sv-badge sv-badge--warning">{m(i18nState.current, "ui.bookmark.hidden")}</span>
    {/if}
    <div class="sv-bookmark-actions">
      {#if mode === "own"}
        <button type="button" class="sv-button sv-button--ghost sv-button--sm" onclick={() => onEdit?.(bookmark)}>
          {m(i18nState.current, "ui.action.edit")}
        </button>
        <button type="button" class="sv-button sv-button--ghost sv-button--sm" onclick={() => onDelete?.(bookmark)}>
          {m(i18nState.current, "ui.action.delete")}
        </button>
      {:else if onReport}
        {#if reported}
          <button type="button" class="sv-button sv-button--sm" disabled>
            {m(i18nState.current, "ui.report.reported")}
          </button>
        {:else}
          <button type="button" class="sv-button sv-button--sm" onclick={() => onReport?.(bookmark)}>
            {m(i18nState.current, "ui.action.report")}
          </button>
        {/if}
      {/if}
    </div>
  </div>
  <a class="sv-bookmark-url" href={bookmark.url} target="_blank" rel="noreferrer">{bookmark.url}</a>
  {#if bookmark.notes}
    <p class="sv-bookmark-notes">{bookmark.notes}</p>
  {/if}
  <div class="sv-bookmark-meta">
    <span>{m(i18nState.current, `ui.visibility.${bookmark.visibility}`)}</span>
    <span>{bookmark.owner}</span>
    <time dateTime={bookmark.createdAt}>{new Date(bookmark.createdAt).toLocaleDateString(i18nState.current.resolvedLanguage)}</time>
  </div>
  {#if bookmark.tags.length > 0}
    <ul class="sv-tag-list">
      {#each bookmark.tags as tag}
        <li><span class="sv-tag">{tag}</span></li>
      {/each}
    </ul>
  {/if}
</li>
