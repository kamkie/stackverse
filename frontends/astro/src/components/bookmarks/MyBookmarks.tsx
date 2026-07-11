import { createSignal, For, onMount, Show } from "solid-js";
import { api } from "../../lib/api";
import { loadBookmarkCursor } from "../../lib/bookmarkCursor";
import { i18n, m } from "../../lib/i18n";
import type { Bookmark } from "../../lib/types";
import BookmarkCard from "../BookmarkCard";
import BookmarkFormDialog from "../BookmarkFormDialog";
import ConfirmDialog from "../ConfirmDialog";
import TagSidebar from "../TagSidebar";
import ClientPage from "../ClientPage";
import { PublicFeedContent } from "./PublicFeed";

interface Props {
  toast: (message: string, tone?: "success" | "danger") => void;
}

export function MyBookmarksContent(props: Props) {
  const [bookmarks, setBookmarks] = createSignal<Bookmark[]>([]);
  const [nextCursor, setNextCursor] = createSignal<string | undefined>(undefined);
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<Error | null>(null);
  const [q, setQ] = createSignal("");
  const [selectedTag, setSelectedTag] = createSignal("");
  const [dialog, setDialog] = createSignal<
    { mode: "create" } | { mode: "edit"; bookmark: Bookmark } | null
  >(null);
  const [deleting, setDeleting] = createSignal<Bookmark | null>(null);
  const [deletePending, setDeletePending] = createSignal(false);
  const [tagReloadKey, setTagReloadKey] = createSignal(0);
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
      setTagReloadKey((current) => current + 1);
    } catch (caught) {
      props.toast(caught instanceof Error ? caught.message : String(caught), "danger");
    } finally {
      setDeletePending(false);
    }
  }

  function selectTag(tag: string) {
    setSelectedTag(tag);
    void load();
  }

  async function reloadAfterSave() {
    await load();
    setTagReloadKey((current) => current + 1);
  }

  onMount(() => {
    void load();
  });

  return (
    <>
      <div class="sv-layout">
        <TagSidebar selected={selectedTag()} reloadKey={tagReloadKey()} onSelect={selectTag} />
        <section class="sv-content">
          <h1 class="sv-page-title">{m(i18n(), "ui.nav.my-bookmarks")}</h1>
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
            <div class="sv-loading"><span class="sv-spinner" /></div>
          ) : error() ? (
            <div class="sv-alert sv-alert--danger" role="alert">{error()?.message}</div>
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
                  <button type="button" class="sv-button" disabled={loading()} onClick={() => load(false)}>
                    {m(i18n(), "ui.action.load-more")}
                  </button>
                </div>
              </Show>
            </>
          )}
        </section>
      </div>

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

export default function MyBookmarks() {
  return <ClientPage requiresAuth fallback={(toast) => <PublicFeedContent toast={toast} />}>{(toast) => <MyBookmarksContent toast={toast} />}</ClientPage>;
}
