import { useMemo, useState } from "react";
import { BookmarkCard } from "../bookmarks/BookmarkCard";
import { BookmarkList } from "../bookmarks/BookmarkList";
import { ReportDialog } from "../bookmarks/ReportDialog";
import { useBookmarks, type Bookmark } from "../bookmarks/queries";
import { useSession } from "../auth/session";
import { useDebouncedValue } from "../lib/useDebouncedValue";
import { useI18n } from "../i18n/I18nProvider";

/** Anonymous view of public bookmarks, with a report action when authenticated. */
export function PublicFeedPage() {
  const { t } = useI18n();
  const session = useSession();
  const [activeTags, setActiveTags] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const q = useDebouncedValue(search, 300);
  const [reporting, setReporting] = useState<Bookmark | null>(null);

  const filters = useMemo(
    () => ({ tags: activeTags, q, visibility: "public" as const }),
    [activeTags, q],
  );
  const bookmarks = useBookmarks(filters);

  const toggleTag = (tag: string) =>
    setActiveTags((tags) =>
      tags.includes(tag) ? tags.filter((existing) => existing !== tag) : [...tags, tag],
    );

  const authenticated = session.data?.authenticated === true;

  return (
    <section className="sv-content">
      <h1 className="sv-page-title">{t("ui.nav.public-feed")}</h1>
      <div className="sv-toolbar">
        <input
          type="search"
          className="sv-input"
          placeholder={t("ui.bookmarks.search.placeholder")}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
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
              authenticated ? (
                <button
                  type="button"
                  className="sv-button sv-button--ghost sv-button--sm"
                  onClick={() => setReporting(bookmark)}
                >
                  {t("ui.action.report")}
                </button>
              ) : undefined
            }
          />
        )}
      />
      {reporting && (
        <ReportDialog bookmark={reporting} onClose={() => setReporting(null)} />
      )}
    </section>
  );
}
