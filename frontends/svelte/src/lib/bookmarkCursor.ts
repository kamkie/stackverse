import { api, queryString } from "./api";
import type { Bookmark, BookmarkCursorPage } from "./types";

export async function loadBookmarkCursor({
  reset,
  current,
  nextCursor,
  params,
}: {
  reset: boolean;
  current: Bookmark[];
  nextCursor: string | undefined;
  params: Record<string, unknown>;
}): Promise<{ bookmarks: Bookmark[]; nextCursor: string | undefined }> {
  const page = await api<BookmarkCursorPage>(
    `/api/v2/bookmarks${queryString({
      size: 20,
      cursor: reset ? undefined : nextCursor,
      ...params,
    })}`,
  );
  return {
    bookmarks: reset ? page.items : [...current, ...page.items],
    nextCursor: page.nextCursor,
  };
}
