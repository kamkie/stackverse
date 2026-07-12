import { createSignal, For, onCleanup, onMount, Show } from "solid-js";
import { api } from "../../lib/api";
import { loadBookmarkCursor } from "../../lib/bookmarkCursor";
import { BOOKMARK_TAG_SELECTED, BOOKMARK_TAGS_CHANGED } from "../../lib/events";
import { i18n, m } from "../../lib/i18n";
import type { Bookmark } from "../../lib/types";
import BookmarkCard from "../BookmarkCard";
import BookmarkFormDialog from "../BookmarkFormDialog";
import ConfirmDialog from "../ConfirmDialog";
import { useIsland } from "../../lib/island";
import { session } from "../../lib/session";
import ToastRegion from "../ToastRegion";

interface Props {
  toast: (message: string, tone?: "success" | "danger") => void;
}

export function BookmarkCollectionContent(props: Props) {
  const [bookmarks, setBookmarks] = createSignal<Bookmark[]>([]);
  const [nextCursor, setNextCursor] = createSignal<string | undefined>(
    undefined,
  );
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<Error | null>(null);
  const [q, setQ] = createSignal("");
  const [selectedTag, setSelectedTag] = createSignal("");
  const [dialog, setDialog] = createSignal<
    { mode: "create" } | { mode: "edit"; bookmark: Bookmark } | null
  >(null);
  const [deleting, setDeleting] = createSignal<Bookmark | null>(null);
  const [deletePending, setDeletePending] = createSignal(false);
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
          q: q(),
          tag: selectedTag() ? [selectedTag()] : [],
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

  async function remove(bookmark: Bookmark) {
    if (deletePending()) return;
    setDeletePending(true);
    try {
      await api<void>(`/api/v1/bookmarks/${bookmark.id}`, { method: "DELETE" });
      props.toast(m(i18n(), "ui.toast.bookmark-deleted"));
      setDeleting(null);
      await load();
      window.dispatchEvent(new Event(BOOKMARK_TAGS_CHANGED));
    } catch (caught) {
      props.toast(
        caught instanceof Error ? caught.message : String(caught),
        "danger",
      );
    } finally {
      setDeletePending(false);
    }
  }

  async function reloadAfterSave() {
    await load();
    window.dispatchEvent(new Event(BOOKMARK_TAGS_CHANGED));
  }

  onMount(() => {
    const selectTag = (event: Event) => {
      setSelectedTag((event as CustomEvent<string>).detail);
      void load();
    };
    window.addEventListener(BOOKMARK_TAG_SELECTED, selectTag);
    onCleanup(() =>
      window.removeEventListener(BOOKMARK_TAG_SELECTED, selectTag),
    );
    void load();
  });

  return (
    <>
      <div class="sv-toolbar">
        <input
          type="search"
          class="sv-input"
          placeholder={m(i18n(), "ui.bookmarks.search.placeholder")}
          value={q()}
          onInput={(event) => setQ(event.currentTarget.value)}
          onChange={() => load()}
        />
        <button
          type="button"
          class="sv-button sv-button--primary"
          onClick={() => setDialog({ mode: "create" })}
        >
          {m(i18n(), "ui.action.add")}
        </button>
      </div>

      {loading() && bookmarks().length === 0 ? (
        <div class="sv-loading">
          <span class="sv-spinner" />
        </div>
      ) : error() ? (
        <div class="sv-alert sv-alert--danger" role="alert">
          {error()?.message}
        </div>
      ) : bookmarks().length === 0 ? (
        <div class="sv-empty">
          {q() || selectedTag()
            ? m(i18n(), "ui.bookmarks.no-matches")
            : m(i18n(), "ui.bookmarks.empty")}
        </div>
      ) : (
        <>
          <ul class="sv-card-list">
            <For each={bookmarks()}>
              {(bookmark) => (
                <BookmarkCard
                  bookmark={bookmark}
                  mode="own"
                  onEdit={(item) => setDialog({ mode: "edit", bookmark: item })}
                  onDelete={(item) => setDeleting(item)}
                />
              )}
            </For>
          </ul>
          <Show when={nextCursor()}>
            <div class="sv-load-more">
              <button
                type="button"
                class="sv-button"
                disabled={loading()}
                onClick={() => load(false)}
              >
                {m(i18n(), "ui.action.load-more")}
              </button>
            </div>
          </Show>
        </>
      )}
      <Show when={dialog()}>
        {(currentDialog) => {
          const current = currentDialog();
          return (
            <BookmarkFormDialog
              bookmark={current.mode === "edit" ? current.bookmark : undefined}
              onSaved={reloadAfterSave}
              onClose={() => setDialog(null)}
            />
          );
        }}
      </Show>

      <Show when={deleting()}>
        {(bookmark) => (
          <ConfirmDialog
            title={`${m(i18n(), "ui.action.delete")} - ${bookmark().title}`}
            body={m(i18n(), "ui.confirm.delete-bookmark")}
            ctx={`bookmark:${bookmark().id}`}
            confirmLabel={m(i18n(), "ui.action.delete")}
            cancelLabel={m(i18n(), "ui.action.cancel")}
            pending={deletePending()}
            onConfirm={() => remove(bookmark())}
            onClose={() => setDeleting(null)}
          />
        )}
      </Show>
    </>
  );
}

export default function BookmarkCollection() {
  const island = useIsland();

  return (
    <Show
      when={island.ready()}
      fallback={
        <div class="sv-loading">
          <span class="sv-spinner" />
        </div>
      }
    >
      <Show
        when={session()?.authenticated}
        fallback={
          <div class="sv-alert sv-alert--danger" role="alert">
            401
          </div>
        }
      >
        <BookmarkCollectionContent toast={island.toast} />
      </Show>
      <ToastRegion toasts={island.toasts()} />
    </Show>
  );
}
