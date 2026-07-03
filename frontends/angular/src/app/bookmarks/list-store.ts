import { computed, effect, signal, untracked, type Signal } from '@angular/core';
import type { Bookmark } from '../api/types';
import { BookmarksApi, type BookmarkFilters } from './api';

interface ListState {
  items: Bookmark[];
  nextCursor: string | undefined;
  error: unknown;
  /** True until the first slice for the current filters has arrived. */
  pending: boolean;
  fetchingMore: boolean;
}

const INITIAL: ListState = {
  items: [],
  nextCursor: undefined,
  error: null,
  pending: true,
  fetchingMore: false,
};

/**
 * Cursor-paginated bookmark list with the "load more" UX of
 * `GET /api/v2/bookmarks`: restarts from the first slice whenever the filters
 * change, appends slices on `loadMore()`. Instantiate as a component field —
 * the constructor registers an effect.
 */
export class BookmarkListStore {
  private readonly state = signal<ListState>(INITIAL);
  private generation = 0;

  readonly items: Signal<Bookmark[]> = computed(() => this.state().items);
  readonly pending: Signal<boolean> = computed(() => this.state().pending);
  readonly error: Signal<unknown> = computed(() => this.state().error);
  readonly hasMore: Signal<boolean> = computed(() => this.state().nextCursor !== undefined);
  readonly fetchingMore: Signal<boolean> = computed(() => this.state().fetchingMore);

  constructor(
    private readonly api: BookmarksApi,
    private readonly filters: Signal<BookmarkFilters>,
    private readonly enabled: Signal<boolean> = signal(true),
  ) {
    effect(() => {
      if (!this.enabled()) return;
      const current = this.filters();
      untracked(() => void this.loadFirst(current));
    });
  }

  /** Restarts from the first slice (after a mutation or filter change). */
  reload(): void {
    if (!untracked(this.enabled)) return;
    void this.loadFirst(untracked(this.filters));
  }

  loadMore(): void {
    const { nextCursor, fetchingMore } = untracked(() => this.state());
    if (nextCursor === undefined || fetchingMore) return;
    const generation = this.generation;
    this.state.update((s) => ({ ...s, fetchingMore: true }));
    this.api
      .listBookmarks(untracked(this.filters), nextCursor)
      .then((page) => {
        if (generation !== this.generation) return;
        this.state.update((s) => ({
          ...s,
          items: [...s.items, ...page.items],
          nextCursor: page.nextCursor,
          fetchingMore: false,
        }));
      })
      .catch((error: unknown) => {
        if (generation !== this.generation) return;
        this.state.update((s) => ({ ...s, error, fetchingMore: false }));
      });
  }

  private async loadFirst(filters: BookmarkFilters): Promise<void> {
    const generation = ++this.generation;
    this.state.update((s) => ({ ...s, pending: s.items.length === 0, error: null }));
    try {
      const page = await this.api.listBookmarks(filters);
      if (generation !== this.generation) return;
      this.state.set({
        items: page.items,
        nextCursor: page.nextCursor,
        error: null,
        pending: false,
        fetchingMore: false,
      });
    } catch (error) {
      if (generation !== this.generation) return;
      this.state.set({ ...INITIAL, pending: false, error });
    }
  }
}
