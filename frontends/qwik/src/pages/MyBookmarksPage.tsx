import {
  $,
  component$,
  useStore,
  useVisibleTask$,
  type PropFunction,
} from "@builder.io/qwik";
import BookmarkCard from "../components/BookmarkCard";
import BookmarkFormDialog from "../components/BookmarkFormDialog";
import ConfirmDialog from "../components/ConfirmDialog";
import TagSidebar from "../components/TagSidebar";
import { api } from "../lib/api";
import { loadBookmarkCursor } from "../lib/bookmarkCursor";
import { m, type I18nState } from "../lib/i18n";
import type { Bookmark } from "../lib/types";

interface Props {
  i18n: I18nState;
  toast$: PropFunction<(message: string, tone?: "success" | "danger") => void>;
}

type BookmarkDialog = { mode: "create" } | { mode: "edit"; bookmark: Bookmark };

export default component$<Props>((props) => {
  const state = useStore<{
    bookmarks: Bookmark[];
    nextCursor?: string;
    loading: boolean;
    error: string;
    q: string;
    selectedTag: string;
    dialog: BookmarkDialog | null;
    deleting: Bookmark | null;
    tagReloadKey: number;
    loadRequest: number;
  }>({
    bookmarks: [],
    nextCursor: undefined,
    loading: true,
    error: "",
    q: "",
    selectedTag: "",
    dialog: null,
    deleting: null,
    tagReloadKey: 0,
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
          q: state.q,
          tag: state.selectedTag ? [state.selectedTag] : [],
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
      <div class="sv-layout">
        <TagSidebar
          i18n={props.i18n}
          selected={state.selectedTag}
          reloadKey={state.tagReloadKey}
          onSelect$={async (tag) => {
            state.selectedTag = tag;
            await load$();
          }}
        />
        <section class="sv-content">
          <h1 class="sv-page-title">{m(props.i18n, "ui.nav.my-bookmarks")}</h1>
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
            <button
              type="button"
              class="sv-button sv-button--primary"
              onClick$={() => (state.dialog = { mode: "create" })}
            >
              {m(props.i18n, "ui.action.add")}
            </button>
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
            <div class="sv-empty">
              {state.q || state.selectedTag
                ? m(props.i18n, "ui.bookmarks.no-matches")
                : m(props.i18n, "ui.bookmarks.empty")}
            </div>
          ) : (
            <>
              <ul class="sv-card-list">
                {state.bookmarks.map((bookmark) => (
                  <BookmarkCard
                    key={bookmark.id}
                    i18n={props.i18n}
                    bookmark={bookmark}
                    mode="own"
                    onEdit$={(item) =>
                      (state.dialog = { mode: "edit", bookmark: item })
                    }
                    onDelete$={(item) => (state.deleting = item)}
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
      </div>

      {state.dialog ? (
        <BookmarkFormDialog
          i18n={props.i18n}
          bookmark={
            state.dialog.mode === "edit" ? state.dialog.bookmark : undefined
          }
          onSaved$={async () => {
            await load$();
            state.tagReloadKey += 1;
          }}
          onClose$={() => (state.dialog = null)}
        />
      ) : null}

      {state.deleting ? (
        <ConfirmDialog
          title={`${m(props.i18n, "ui.action.delete")} - ${state.deleting.title}`}
          body={m(props.i18n, "ui.confirm.delete-bookmark")}
          ctx={`bookmark:${state.deleting.id}`}
          confirmLabel={m(props.i18n, "ui.action.delete")}
          cancelLabel={m(props.i18n, "ui.action.cancel")}
          onConfirm$={async () => {
            if (!state.deleting) return;
            try {
              await api<void>(`/api/v1/bookmarks/${state.deleting.id}`, {
                method: "DELETE",
              });
              await props.toast$(m(props.i18n, "ui.toast.bookmark-deleted"));
              state.deleting = null;
              await load$();
              state.tagReloadKey += 1;
            } catch (caught) {
              await props.toast$(
                caught instanceof Error ? caught.message : String(caught),
                "danger",
              );
            }
          }}
          onClose$={() => (state.deleting = null)}
        />
      ) : null}
    </>
  );
});
