import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { api, unwrap } from "../api/client";
import type { components } from "../api/schema";

/**
 * The contract requires `visibility` and `tags` on every Bookmark *response*
 * (the allOf sibling's `required` in spec/openapi.yaml) even though both are
 * optional on BookmarkInput. openapi-typescript drops `required` entries that
 * point at inherited properties, so restore them on the app-facing type.
 */
export type Bookmark = components["schemas"]["Bookmark"] &
  Required<Pick<components["schemas"]["Bookmark"], "visibility" | "tags">>;
export type BookmarkInput = components["schemas"]["BookmarkInput"];
export type Visibility = components["schemas"]["Visibility"];
export type ReportInput = components["schemas"]["ReportInput"];

/** The generated cursor page with the response-required Bookmark fields restored. */
export type BookmarkCursorPage = Omit<
  components["schemas"]["BookmarkCursorPage"],
  "items"
> & {
  items: Bookmark[];
};

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
export function useBookmarks(
  filters: BookmarkFilters,
  options: { enabled?: boolean } = {},
) {
  return useInfiniteQuery({
    queryKey: ["bookmarks", filters],
    enabled: options.enabled ?? true,
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
      ) as BookmarkCursorPage,
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
  const invalidate = useInvalidateMyReports();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: ReportInput }) =>
      unwrap(
        await api.POST("/api/v1/bookmarks/{id}/reports", {
          params: { path: { id } },
          body,
        }),
      ),
    onSuccess: invalidate,
  });
}

export type Report = components["schemas"]["Report"];
export type ReportStatus = components["schemas"]["ReportStatus"];

/** The caller's own reports (SPEC rule 13) — the reporter's feedback loop. */
export function useMyReports(status: ReportStatus | "", page: number) {
  return useQuery({
    queryKey: ["my-reports", status, page],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/v1/reports", {
          params: { query: { ...(status ? { status } : {}), page } },
        }),
      ),
  });
}

function useInvalidateMyReports() {
  const queryClient = useQueryClient();
  return () => void queryClient.invalidateQueries({ queryKey: ["my-reports"] });
}

export function useUpdateMyReport() {
  const invalidate = useInvalidateMyReports();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: ReportInput }) =>
      unwrap(
        await api.PUT("/api/v1/reports/{id}", { params: { path: { id } }, body }),
      ),
    onSuccess: invalidate,
  });
}

export function useWithdrawReport() {
  const invalidate = useInvalidateMyReports();
  return useMutation({
    mutationFn: async (id: string) =>
      unwrap(
        await api.DELETE("/api/v1/reports/{id}", { params: { path: { id } } }),
      ),
    onSuccess: invalidate,
  });
}
