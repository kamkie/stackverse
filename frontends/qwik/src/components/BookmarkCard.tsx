import { component$, type PropFunction } from "@builder.io/qwik";
import { m, type I18nState } from "../lib/i18n";
import type { Bookmark } from "../lib/types";

interface Props {
  i18n: I18nState;
  bookmark: Bookmark;
  mode: "own" | "feed";
  reported?: boolean;
  onEdit$?: PropFunction<(bookmark: Bookmark) => void>;
  onDelete$?: PropFunction<(bookmark: Bookmark) => void>;
  onReport$?: PropFunction<(bookmark: Bookmark) => void>;
}

export default component$<Props>((props) => {
  return (
    <li class="sv-card sv-bookmark" data-ctx={`bookmark:${props.bookmark.id}`}>
      <div class="sv-bookmark-head">
        <h2 class="sv-bookmark-title">
          <a href={props.bookmark.url} target="_blank" rel="noreferrer">
            {props.bookmark.title}
          </a>
        </h2>
        {props.bookmark.status === "hidden" ? (
          <span class="sv-badge sv-badge--warning">
            {m(props.i18n, "ui.bookmark.hidden")}
          </span>
        ) : null}
        <div class="sv-bookmark-actions">
          {props.mode === "own" ? (
            <>
              <button
                type="button"
                class="sv-button sv-button--ghost sv-button--sm"
                onClick$={() => props.onEdit$?.(props.bookmark)}
              >
                {m(props.i18n, "ui.action.edit")}
              </button>
              <button
                type="button"
                class="sv-button sv-button--ghost sv-button--sm"
                onClick$={() => props.onDelete$?.(props.bookmark)}
              >
                {m(props.i18n, "ui.action.delete")}
              </button>
            </>
          ) : null}
          {props.mode === "feed" && props.onReport$ ? (
            props.reported ? (
              <button type="button" class="sv-button sv-button--sm" disabled>
                {m(props.i18n, "ui.report.reported")}
              </button>
            ) : (
              <button
                type="button"
                class="sv-button sv-button--sm"
                onClick$={() => props.onReport$?.(props.bookmark)}
              >
                {m(props.i18n, "ui.action.report")}
              </button>
            )
          ) : null}
        </div>
      </div>
      <a class="sv-bookmark-url" href={props.bookmark.url} target="_blank" rel="noreferrer">
        {props.bookmark.url}
      </a>
      {props.bookmark.notes ? <p class="sv-bookmark-notes">{props.bookmark.notes}</p> : null}
      <div class="sv-bookmark-meta">
        <span>{m(props.i18n, `ui.visibility.${props.bookmark.visibility}`)}</span>
        <span>{props.bookmark.owner}</span>
        <time dateTime={props.bookmark.createdAt}>
          {new Date(props.bookmark.createdAt).toLocaleDateString(props.i18n.resolvedLanguage)}
        </time>
      </div>
      {props.bookmark.tags.length > 0 ? (
        <ul class="sv-tag-list">
          {props.bookmark.tags.map((tag) => (
            <li key={tag}>
              <span class="sv-tag">{tag}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </li>
  );
});
