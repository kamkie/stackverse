import {
  $,
  component$,
  useStore,
  useVisibleTask$,
  type PropFunction,
} from "@builder.io/qwik";
import BookmarkCard from "../components/BookmarkCard";
import ReportDialog from "../components/ReportDialog";
import { loadBookmarkCursor } from "../lib/bookmarkCursor";
import { m, type I18nState } from "../lib/i18n";
import { isReported } from "../lib/reportedStore";
import type { Bookmark, Session } from "../lib/types";

interface Props {
  i18n: I18nState;
  session: Session | null;
  toast$: PropFunction<(message: string, tone?: "success" | "danger") => void>;
}

export default component$<Props>((props) => {
  const state = useStore<{
    bookmarks: Bookmark[];
    nextCursor?: string;
    loading: boolean;
    error: string;
    q: string;
    reporting: Bookmark | null;
    loadRequest: number;
  }>({
    bookmarks: [],
    nextCursor: undefined,
    loading: true,
    error: "",
    q: "",
    reporting: null,
    loadRequest: 0,
  });

  const load$ = $(async (reset = true) => {
    const request = ++state.loadRequest;
    state.loading = true;
    state.error = "";
    try {
      const loaded = await loadBookmarkCursor({
        reset,
        current: state.bookmarks,
        nextCursor: state.nextCursor,
        params: {
          visibility: "public",
          q: state.q,
        },
      });
      if (request !== state.loadRequest) return;
      state.bookmarks = loaded.bookmarks;
      state.nextCursor = loaded.nextCursor;
    } catch (caught) {
      if (request === state.loadRequest) {
        state.error = caught instanceof Error ? caught.message : String(caught);
      }
    } finally {
      if (request === state.loadRequest) state.loading = false;
    }
  });

  useVisibleTask$(() => {
    void load$();
  });

  return (
    <>
      <section class="sv-content">
        <h1 class="sv-page-title">{m(props.i18n, "ui.nav.public-feed")}</h1>
        <div class="sv-toolbar">
          <input
            type="search"
            class="sv-input"
            placeholder={m(props.i18n, "ui.bookmarks.search.placeholder")}
            value={state.q}
            onInput$={(event: Event) =>
              (state.q = (event.target as HTMLInputElement).value)
            }
            onChange$={() => {
              void load$();
            }}
          />
        </div>

        {state.loading && state.bookmarks.length === 0 ? (
          <div class="sv-loading">
            <span class="sv-spinner" />
          </div>
        ) : state.error ? (
          <div class="sv-alert sv-alert--danger" role="alert">
            {state.error}
          </div>
        ) : state.bookmarks.length === 0 ? (
          <div class="sv-empty">{m(props.i18n, "ui.bookmarks.no-matches")}</div>
        ) : (
          <>
            <ul class="sv-card-list">
              {state.bookmarks.map((bookmark) => (
                <BookmarkCard
                  key={bookmark.id}
                  i18n={props.i18n}
                  bookmark={bookmark}
                  mode="feed"
                  reported={isReported(bookmark.id)}
                  onReport$={
                    props.session?.authenticated
                      ? (item) => {
                          state.reporting = item;
                        }
                      : undefined
                  }
                />
              ))}
            </ul>
            {state.nextCursor ? (
              <div class="sv-load-more">
                <button
                  type="button"
                  class="sv-button"
                  disabled={state.loading}
                  onClick$={() => void load$(false)}
                >
                  {m(props.i18n, "ui.action.load-more")}
                </button>
              </div>
            ) : null}
          </>
        )}
      </section>

      {state.reporting ? (
        <ReportDialog
          i18n={props.i18n}
          bookmark={state.reporting}
          toast$={props.toast$}
          onDone$={load$}
          onClose$={() => (state.reporting = null)}
        />
      ) : null}
    </>
  );
});
