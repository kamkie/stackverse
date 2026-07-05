import type { ReactNode } from "react";
import type { UseInfiniteQueryResult, InfiniteData } from "@tanstack/react-query";
import { ErrorState, Loading } from "../components/states";
import { useI18n } from "../i18n/I18nContext";
import type { Bookmark, BookmarkCursorPage } from "./queries";

interface BookmarkListProps {
  query: UseInfiniteQueryResult<InfiniteData<BookmarkCursorPage>, Error>;
  renderBookmark: (bookmark: Bookmark) => ReactNode;
  /** Shown when the list is empty; defaults to the "no bookmarks yet" message. */
  emptyMessage?: string | undefined;
}

/** Cursor-paginated list with the "load more" UX of `GET /api/v2/bookmarks`. */
export function BookmarkList({ query, renderBookmark, emptyMessage }: BookmarkListProps) {
  const { t } = useI18n();

  if (query.isPending) return <Loading />;
  if (query.isError) return <ErrorState error={query.error} />;

  const bookmarks = query.data.pages.flatMap((page) => page.items);

  if (bookmarks.length === 0) {
    return <div className="sv-empty">{emptyMessage ?? t("ui.bookmarks.empty")}</div>;
  }

  return (
    <>
      <ul className="sv-card-list">{bookmarks.map(renderBookmark)}</ul>
      {query.hasNextPage && (
        <div className="sv-load-more">
          <button
            type="button"
            className="sv-button"
            onClick={() => void query.fetchNextPage()}
            disabled={query.isFetchingNextPage}
          >
            {t("ui.action.load-more")}
          </button>
        </div>
      )}
    </>
  );
}
