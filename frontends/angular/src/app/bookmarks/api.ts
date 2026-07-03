import { HttpClient } from '@angular/common/http';
import { effect, inject, Injectable, signal, untracked } from '@angular/core';
import { call, params } from '../api/http';
import type {
  Bookmark,
  BookmarkCursorPage,
  BookmarkInput,
  Page,
  Report,
  ReportInput,
  ReportStatus,
  TagList,
  Visibility,
} from '../api/types';
import { SessionStore } from '../auth/session';

export interface BookmarkFilters {
  tags: string[];
  q: string;
  visibility?: Visibility;
}

@Injectable({ providedIn: 'root' })
export class BookmarksApi {
  private readonly http = inject(HttpClient);

  /**
   * One slice of the cursor-paginated `GET /api/v2/bookmarks` — the successor
   * of the deprecated offset v1 listing. The cursor is opaque: it goes
   * straight from `nextCursor` back into the next request, never parsed.
   */
  listBookmarks(filters: BookmarkFilters, cursor?: string): Promise<BookmarkCursorPage> {
    return call(
      this.http.get<BookmarkCursorPage>('/api/v2/bookmarks', {
        params: params({
          tag: filters.tags,
          q: filters.q,
          visibility: filters.visibility,
          cursor,
        }),
      }),
    );
  }

  getBookmark(id: string): Promise<Bookmark> {
    return call(this.http.get<Bookmark>(`/api/v1/bookmarks/${id}`));
  }

  createBookmark(body: BookmarkInput): Promise<Bookmark> {
    return call(this.http.post<Bookmark>('/api/v1/bookmarks', body));
  }

  updateBookmark(id: string, body: BookmarkInput): Promise<Bookmark> {
    return call(this.http.put<Bookmark>(`/api/v1/bookmarks/${id}`, body));
  }

  deleteBookmark(id: string): Promise<void> {
    return call(this.http.delete<void>(`/api/v1/bookmarks/${id}`));
  }

  reportBookmark(id: string, body: ReportInput): Promise<Report> {
    return call(this.http.post<Report>(`/api/v1/bookmarks/${id}/reports`, body));
  }

  listTags(): Promise<TagList> {
    return call(this.http.get<TagList>('/api/v1/tags'));
  }

  /** The caller's own reports (SPEC rule 13) — the reporter's feedback loop. */
  listMyReports(status: ReportStatus | '', page: number): Promise<Page<Report>> {
    return call(
      this.http.get<Page<Report>>('/api/v1/reports', { params: params({ status, page }) }),
    );
  }

  updateMyReport(id: string, body: ReportInput): Promise<Report> {
    return call(this.http.put<Report>(`/api/v1/reports/${id}`, body));
  }

  withdrawReport(id: string): Promise<void> {
    return call(this.http.delete<void>(`/api/v1/reports/${id}`));
  }
}

/**
 * The caller's tags with usage counts (`GET /api/v1/tags`), shared by the
 * sidebar and reloaded after bookmark mutations. Follows the session: loads
 * when authenticated, clears when the session ends.
 */
@Injectable({ providedIn: 'root' })
export class TagsStore {
  private readonly api = inject(BookmarksApi);
  private readonly session = inject(SessionStore);
  private readonly state = signal<TagList | undefined>(undefined);
  private generation = 0;

  readonly tags = this.state.asReadonly();

  constructor() {
    effect(() => {
      const authenticated = this.session.authenticated();
      untracked(() => {
        if (authenticated) this.reload();
        else this.state.set(undefined);
      });
    });
  }

  reload(): void {
    const generation = ++this.generation;
    this.api
      .listTags()
      .then((tags) => {
        if (generation === this.generation) this.state.set(tags);
      })
      .catch(() => {
        if (generation === this.generation) this.state.set(undefined);
      });
  }
}
