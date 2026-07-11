import { createSignal, For, onMount, Show } from "solid-js";
import BookmarkCard from "../components/BookmarkCard";
import ReportDialog from "../components/ReportDialog";
import { loadBookmarkCursor } from "../lib/bookmarkCursor";
import { i18n, m } from "../lib/i18n";
import { isReported } from "../lib/reportedStore";
import { session } from "../lib/session";
import type { Bookmark } from "../lib/types";

interface Props {
  toast: (message: string, tone?: "success" | "danger") => void;
}

export default function PublicFeedPage(props: Props) {
  const [bookmarks, setBookmarks] = createSignal<Bookmark[]>([]);
  const [nextCursor, setNextCursor] = createSignal<string | undefined>(undefined);
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<Error | null>(null);
  const [q, setQ] = createSignal("");
  const [reporting, setReporting] = createSignal<Bookmark | null>(null);
  let loadRequest = 0;

  async function load(reset = true) {
    const request = ++loadRequest;
    setLoading(true);
    setError(null);
    try {
      const loaded = await loadBookmarkCursor({
        reset,
        current: bookmarks(),
        nextCursor: nextCursor(),
        params: {
          visibility: "public",
          q: q(),
        },
      });
      if (request !== loadRequest) return;
      setBookmarks(loaded.bookmarks);
      setNextCursor(loaded.nextCursor);
    } catch (caught) {
      if (request === loadRequest) {
        setError(caught instanceof Error ? caught : new Error(String(caught)));
      }
    } finally {
      if (request === loadRequest) setLoading(false);
    }
  }

  onMount(() => {
    void load();
  });

  return (
    <>
      <section class="sv-content">
        <h1 class="sv-page-title">{m(i18n(), "ui.nav.public-feed")}</h1>
        <div class="sv-toolbar">
          <input
            type="search"
            class="sv-input"
            placeholder={m(i18n(), "ui.bookmarks.search.placeholder")}
            value={q()}
            onInput={(event) => setQ(event.currentTarget.value)}
            onChange={() => load()}
          />
        </div>

        {loading() && bookmarks().length === 0 ? (
          <div class="sv-loading"><span class="sv-spinner" /></div>
        ) : error() ? (
          <div class="sv-alert sv-alert--danger" role="alert">{error()?.message}</div>
        ) : bookmarks().length === 0 ? (
          <div class="sv-empty">{m(i18n(), "ui.bookmarks.no-matches")}</div>
        ) : (
          <>
            <ul class="sv-card-list">
              <For each={bookmarks()}>
                {(bookmark) => (
                  <BookmarkCard
                    bookmark={bookmark}
                    mode="feed"
                    reported={isReported(bookmark.id)}
                    onReport={session()?.authenticated ? (item) => setReporting(item) : undefined}
                  />
                )}
              </For>
            </ul>
            <Show when={nextCursor()}>
              <div class="sv-load-more">
                <button type="button" class="sv-button" disabled={loading()} onClick={() => load(false)}>
                  {m(i18n(), "ui.action.load-more")}
                </button>
              </div>
            </Show>
          </>
        )}
      </section>

      <Show when={reporting()}>
        {(bookmark) => (
          <ReportDialog
            bookmark={bookmark()}
            toast={props.toast}
            onDone={() => load()}
            onClose={() => setReporting(null)}
          />
        )}
      </Show>
    </>
  );
}
