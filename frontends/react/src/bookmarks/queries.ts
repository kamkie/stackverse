import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { api, unwrap } from "../api/client";
import type { components } from "../api/schema";

export type Bookmark = components["schemas"]["Bookmark"];
export type BookmarkInput = components["schemas"]["BookmarkInput"];
export type Visibility = components["schemas"]["Visibility"];
export type ReportInput = components["schemas"]["ReportInput"];

export interface BookmarkFilters {
  tags: string[];
  q: string;
  visibility?: Visibility;
}

/**
 * Cursor-paginated bookmark list from `GET /api/v2/bookmarks` — the successor
 * of the deprecated offset v1 listing. The cursor is opaque: it goes straight
 * from `nextCursor` back into the next request, never parsed.
 */
export function useBookmarks(filters: BookmarkFilters) {
  return useInfiniteQuery({
    queryKey: ["bookmarks", filters],
    queryFn: async ({ pageParam }) =>
      unwrap(
        await api.GET("/api/v2/bookmarks", {
          params: {
            query: {
              ...(filters.tags.length > 0 ? { tag: filters.tags } : {}),
              ...(filters.q ? { q: filters.q } : {}),
              ...(filters.visibility ? { visibility: filters.visibility } : {}),
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
  });
}

export function useTags() {
  return useQuery({
    queryKey: ["tags"],
    queryFn: async () => unwrap(await api.GET("/api/v1/tags")),
  });
}

function useInvalidateBookmarks() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: ["bookmarks"] });
    void queryClient.invalidateQueries({ queryKey: ["tags"] });
  };
}

export function useCreateBookmark() {
  const invalidate = useInvalidateBookmarks();
  return useMutation({
    mutationFn: async (body: BookmarkInput) =>
      unwrap(await api.POST("/api/v1/bookmarks", { body })),
    onSuccess: invalidate,
  });
}

export function useUpdateBookmark() {
  const invalidate = useInvalidateBookmarks();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: BookmarkInput }) =>
      unwrap(
        await api.PUT("/api/v1/bookmarks/{id}", {
          params: { path: { id } },
          body,
        }),
      ),
    onSuccess: invalidate,
  });
}

export function useDeleteBookmark() {
  const invalidate = useInvalidateBookmarks();
  return useMutation({
    mutationFn: async (id: string) =>
      unwrap(
        await api.DELETE("/api/v1/bookmarks/{id}", { params: { path: { id } } }),
      ),
    onSuccess: invalidate,
  });
}

export function useReportBookmark() {
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: ReportInput }) =>
      unwrap(
        await api.POST("/api/v1/bookmarks/{id}/reports", {
          params: { path: { id } },
          body,
        }),
      ),
  });
}
