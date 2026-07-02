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
import { useDebouncedValue } from "../lib/useDebouncedValue";
import { useI18n } from "../i18n/I18nProvider";

export function MyBookmarksPage() {
  const { t } = useI18n();
  const [activeTags, setActiveTags] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const q = useDebouncedValue(search, 300);
  const [dialog, setDialog] = useState<
    { mode: "create" } | { mode: "edit"; bookmark: Bookmark } | null
  >(null);

  const filters = useMemo(() => ({ tags: activeTags, q }), [activeTags, q]);
  const bookmarks = useBookmarks(filters);
  const deleteBookmark = useDeleteBookmark();

  const toggleTag = (tag: string) =>
    setActiveTags((tags) =>
      tags.includes(tag) ? tags.filter((existing) => existing !== tag) : [...tags, tag],
    );

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
                    onClick={() => deleteBookmark.mutate(bookmark.id)}
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
      </section>
    </div>
  );
}
