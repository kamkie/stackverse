import { component$, useStore, useVisibleTask$ } from "@builder.io/qwik";
import { api } from "../lib/api";
import { m, type I18nState } from "../lib/i18n";
import type { Bookmark } from "../lib/types";

export default component$<{ i18n: I18nState; bookmarkId: string }>((props) => {
  const state = useStore<{ bookmark: Bookmark | null; failed: boolean }>({
    bookmark: null,
    failed: false,
  });

  useVisibleTask$(({ cleanup, track }) => {
    const bookmarkId = track(() => props.bookmarkId);
    let cancelled = false;
    state.bookmark = null;
    state.failed = false;
    api<Bookmark>(`/api/v1/bookmarks/${bookmarkId}`)
      .then((loaded) => {
        if (!cancelled) state.bookmark = loaded;
      })
      .catch(() => {
        if (!cancelled) state.failed = true;
      });
    cleanup(() => {
      cancelled = true;
    });
  });

  if (!state.bookmark) {
    return (
      <>
        <span class="sv-cell-mono">{props.bookmarkId}</span>
        {state.failed ? (
          <div class="sv-field-hint">
            {m(props.i18n, "ui.reports.bookmark-unavailable")}
          </div>
        ) : null}
      </>
    );
  }

  return (
    <>
      <strong>{state.bookmark.title}</strong>
      <div>
        <a
          class="sv-bookmark-url"
          href={state.bookmark.url}
          target="_blank"
          rel="noreferrer"
        >
          {state.bookmark.url}
        </a>
      </div>
    </>
  );
});
