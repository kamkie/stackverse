import { useMemo, useState } from "react";
import { BookmarkCard } from "../bookmarks/BookmarkCard";
import { BookmarkFormDialog } from "../bookmarks/BookmarkFormDialog";
import { BookmarkList } from "../bookmarks/BookmarkList";
import { TagSidebar } from "../bookmarks/TagSidebar";
import {
  useBookmarks,
  useDeleteBookmark,
  type Bookmark,
} from "../bookmarks/queries";
import { useSession } from "../auth/session";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { Loading, LoginPrompt } from "../components/states";
import { useToast } from "../components/Toast";
import { useDebouncedValue } from "../lib/useDebouncedValue";
import { useI18n } from "../i18n/I18nProvider";

export function MyBookmarksPage() {
  const { t } = useI18n();
  const toast = useToast();
  const session = useSession();
  const [activeTags, setActiveTags] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const q = useDebouncedValue(search, 300);
  const [dialog, setDialog] = useState<
    { mode: "create" } | { mode: "edit"; bookmark: Bookmark } | null
  >(null);
  const [deleting, setDeleting] = useState<Bookmark | null>(null);

  const filters = useMemo(() => ({ tags: activeTags, q }), [activeTags, q]);
  const bookmarks = useBookmarks(filters);
  const deleteBookmark = useDeleteBookmark();

  const toggleTag = (tag: string) =>
    setActiveTags((tags) =>
      tags.includes(tag) ? tags.filter((existing) => existing !== tag) : [...tags, tag],
    );

  // This page is owner-only: anonymous visitors get a login prompt instead of
  // an Add/search toolbar whose every action would just 401. Session-based on
  // purpose — /api/v1/me is 403 for blocked users, who must still reach the
  // list to see the localized "blocked" error from the bookmarks call.
  if (session.isPending) {
    return (
      <section className="sv-content">
        <h1 className="sv-page-title">{t("ui.nav.my-bookmarks")}</h1>
        <Loading />
      </section>
    );
  }

  if (session.data?.authenticated !== true) {
    return (
      <section className="sv-content">
        <h1 className="sv-page-title">{t("ui.nav.my-bookmarks")}</h1>
        <LoginPrompt />
      </section>
    );
  }

  const filtered = q !== "" || activeTags.length > 0;

  return (
    <div className="sv-layout">
      <TagSidebar activeTags={activeTags} onToggleTag={toggleTag} />
      <section className="sv-content">
        <h1 className="sv-page-title">{t("ui.nav.my-bookmarks")}</h1>
        <div className="sv-toolbar">
          <input
            type="search"
            className="sv-input"
            placeholder={t("ui.bookmarks.search.placeholder")}
            aria-label={t("ui.bookmarks.search.placeholder")}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <button
            type="button"
            className="sv-button sv-button--primary"
            onClick={() => setDialog({ mode: "create" })}
          >
            {t("ui.action.add")}
          </button>
        </div>
        <BookmarkList
          query={bookmarks}
          emptyMessage={filtered ? t("ui.bookmarks.no-matches") : undefined}
          renderBookmark={(bookmark) => (
            <BookmarkCard
              key={bookmark.id}
              bookmark={bookmark}
              activeTags={activeTags}
              onToggleTag={toggleTag}
              actions={
                <>
                  <button
                    type="button"
                    className="sv-button sv-button--ghost sv-button--sm"
                    onClick={() => setDialog({ mode: "edit", bookmark })}
                  >
                    {t("ui.action.edit")}
                  </button>
                  <button
                    type="button"
                    className="sv-button sv-button--ghost sv-button--sm"
                    onClick={() => setDeleting(bookmark)}
                  >
                    {t("ui.action.delete")}
                  </button>
                </>
              }
            />
          )}
        />
        {dialog && (
          <BookmarkFormDialog
            bookmark={dialog.mode === "edit" ? dialog.bookmark : undefined}
            onClose={() => setDialog(null)}
          />
        )}
        {deleting && (
          <ConfirmDialog
            title={`${t("ui.action.delete")} — ${deleting.title}`}
            body={t("ui.confirm.delete-bookmark")}
            confirmLabel={t("ui.action.delete")}
            cancelLabel={t("ui.action.cancel")}
            pending={deleteBookmark.isPending}
            onConfirm={() =>
              deleteBookmark.mutate(deleting.id, {
                onSuccess: () => {
                  setDeleting(null);
                  toast.push(t("ui.toast.bookmark-deleted"), "success");
                },
                onError: (error) =>
                  toast.push(
                    error instanceof Error ? error.message : String(error),
                    "danger",
                  ),
              })
            }
            onClose={() => setDeleting(null)}
          />
        )}
      </section>
    </div>
  );
}
