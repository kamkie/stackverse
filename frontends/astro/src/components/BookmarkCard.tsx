import { For, Show } from "solid-js";
import { i18n, m } from "../lib/i18n";
import type { Bookmark } from "../lib/types";

interface Props {
  bookmark: Bookmark;
  mode: "own" | "feed";
  reported?: boolean;
  onEdit?: (bookmark: Bookmark) => void;
  onDelete?: (bookmark: Bookmark) => void;
  onReport?: (bookmark: Bookmark) => void;
}

export default function BookmarkCard(props: Props) {
  return (
    <li class="sv-card sv-bookmark" data-ctx={`bookmark:${props.bookmark.id}`}>
      <div class="sv-bookmark-head">
        <h2 class="sv-bookmark-title">
          <a href={props.bookmark.url} target="_blank" rel="noreferrer">
            {props.bookmark.title}
          </a>
        </h2>
        <Show when={props.bookmark.status === "hidden"}>
          <span class="sv-badge sv-badge--warning">
            {m(i18n(), "ui.bookmark.hidden")}
          </span>
        </Show>
        <div class="sv-bookmark-actions">
          <Show when={props.mode === "own"}>
            <button
              type="button"
              class="sv-button sv-button--ghost sv-button--sm"
              onClick={() => props.onEdit?.(props.bookmark)}
            >
              {m(i18n(), "ui.action.edit")}
            </button>
            <button
              type="button"
              class="sv-button sv-button--ghost sv-button--sm"
              onClick={() => props.onDelete?.(props.bookmark)}
            >
              {m(i18n(), "ui.action.delete")}
            </button>
          </Show>
          <Show when={props.mode === "feed" && props.onReport}>
            <Show
              when={props.reported}
              fallback={
                <button
                  type="button"
                  class="sv-button sv-button--sm"
                  onClick={() => props.onReport?.(props.bookmark)}
                >
                  {m(i18n(), "ui.action.report")}
                </button>
              }
            >
              <button type="button" class="sv-button sv-button--sm" disabled>
                {m(i18n(), "ui.report.reported")}
              </button>
            </Show>
          </Show>
        </div>
      </div>
      <a class="sv-bookmark-url" href={props.bookmark.url} target="_blank" rel="noreferrer">
        {props.bookmark.url}
      </a>
      <Show when={props.bookmark.notes}>
        {(notes) => <p class="sv-bookmark-notes">{notes()}</p>}
      </Show>
      <div class="sv-bookmark-meta">
        <span>{m(i18n(), `ui.visibility.${props.bookmark.visibility}`)}</span>
        <span>{props.bookmark.owner}</span>
        <time dateTime={props.bookmark.createdAt}>
          {new Date(props.bookmark.createdAt).toLocaleDateString(i18n().resolvedLanguage)}
        </time>
      </div>
      <Show when={props.bookmark.tags.length > 0}>
        <ul class="sv-tag-list">
          <For each={props.bookmark.tags}>
            {(tag) => (
              <li>
                <span class="sv-tag">{tag}</span>
              </li>
            )}
          </For>
        </ul>
      </Show>
    </li>
  );
}
