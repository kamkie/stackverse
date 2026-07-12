import { createSignal, onCleanup, onMount, Show } from "solid-js";
import { api } from "../lib/api";
import { i18n, m } from "../lib/i18n";
import type { Bookmark } from "../lib/types";

const bookmarkRequests = new Map<string, Promise<Bookmark>>();

function loadBookmark(id: string): Promise<Bookmark> {
  const existing = bookmarkRequests.get(id);
  if (existing) return existing;
  const request = api<Bookmark>(`/api/v1/bookmarks/${id}`).catch((error) => {
    bookmarkRequests.delete(id);
    throw error;
  });
  bookmarkRequests.set(id, request);
  return request;
}

export default function BookmarkContext(props: { bookmarkId: string }) {
  const [bookmark, setBookmark] = createSignal<Bookmark | null>(null);
  const [failed, setFailed] = createSignal(false);

  onMount(() => {
    let cancelled = false;
    loadBookmark(props.bookmarkId)
      .then((loaded) => {
        if (!cancelled) setBookmark(loaded);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    onCleanup(() => {
      cancelled = true;
    });
  });

  return (
    <Show
      when={bookmark()}
      fallback={
        <>
          <span class="sv-cell-mono">{props.bookmarkId}</span>
          <Show when={failed()}>
            <div class="sv-field-hint">
              {m(i18n(), "ui.reports.bookmark-unavailable")}
            </div>
          </Show>
        </>
      }
    >
      {(item) => (
        <>
          <strong>{item().title}</strong>
          <div>
            <a
              class="sv-bookmark-url"
              href={item().url}
              target="_blank"
              rel="noreferrer"
            >
              {item().url}
            </a>
          </div>
        </>
      )}
    </Show>
  );
}
