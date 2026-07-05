import { Injector, runInInjectionContext, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { flushAsync } from '../../testing/bundle-fetch';
import type { Bookmark, BookmarkCursorPage } from '../api/types';
import { BookmarkListStore } from './list-store';
import { BookmarksApi, type BookmarkFilters } from './api';

function bookmark(id: string): Bookmark {
  return {
    id,
    owner: 'demo',
    url: `https://example.com/${id}`,
    title: `Bookmark ${id}`,
    notes: '',
    tags: [],
    visibility: 'private',
    status: 'active',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

function createStore(api: BookmarksApi, filters: ReturnType<typeof signal<BookmarkFilters>>) {
  return runInInjectionContext(
    TestBed.inject(Injector),
    () => new BookmarkListStore(api, filters),
  );
}

describe('BookmarkListStore', () => {
  it('loads the first slice, appends more, and reflects nextCursor as hasMore', async () => {
    const filters = signal<BookmarkFilters>({ tags: [], q: '' });
    const listBookmarks = vi.fn(
      async (_filters: BookmarkFilters, cursor?: string): Promise<BookmarkCursorPage> =>
        cursor === 'cursor-2'
          ? { items: [bookmark('b')] }
          : { items: [bookmark('a')], nextCursor: 'cursor-2' },
    );
    const store = createStore({ listBookmarks } as unknown as BookmarksApi, filters);

    TestBed.tick();
    await flushAsync();
    expect(store.items().map((item) => item.id)).toEqual(['a']);
    expect(store.hasMore()).toBe(true);
    expect(store.pending()).toBe(false);

    store.loadMore();
    expect(store.fetchingMore()).toBe(true);
    await flushAsync();

    expect(listBookmarks).toHaveBeenNthCalledWith(2, filters(), 'cursor-2');
    expect(store.items().map((item) => item.id)).toEqual(['a', 'b']);
    expect(store.hasMore()).toBe(false);
    expect(store.fetchingMore()).toBe(false);
  });

  it('discards a late loadMore slice after filters restart the list', async () => {
    const filters = signal<BookmarkFilters>({ tags: [], q: '' });
    const calls: {
      filters: BookmarkFilters;
      cursor: string | undefined;
      resolve: (page: BookmarkCursorPage) => void;
    }[] = [];
    const listBookmarks = vi.fn(
      (current: BookmarkFilters, cursor?: string) =>
        new Promise<BookmarkCursorPage>((resolve) =>
          calls.push({ filters: current, cursor, resolve }),
        ),
    );
    const store = createStore({ listBookmarks } as unknown as BookmarksApi, filters);

    TestBed.tick();
    calls[0].resolve({ items: [bookmark('a')], nextCursor: 'cursor-2' });
    await flushAsync();

    store.loadMore();
    expect(calls[1].cursor).toBe('cursor-2');
    filters.set({ tags: ['fresh'], q: '' });
    TestBed.tick();
    expect(calls[2].filters).toEqual({ tags: ['fresh'], q: '' });

    calls[2].resolve({ items: [bookmark('fresh')] });
    await flushAsync();
    calls[1].resolve({ items: [bookmark('late')] });
    await flushAsync();

    expect(store.items().map((item) => item.id)).toEqual(['fresh']);
    expect(store.hasMore()).toBe(false);
    expect(store.fetchingMore()).toBe(false);
  });

  it('keeps existing items visible without first-load pending during reload', async () => {
    const filters = signal<BookmarkFilters>({ tags: [], q: '' });
    const calls: { resolve: (page: BookmarkCursorPage) => void }[] = [];
    const listBookmarks = vi.fn(
      () => new Promise<BookmarkCursorPage>((resolve) => calls.push({ resolve })),
    );
    const store = createStore({ listBookmarks } as unknown as BookmarksApi, filters);

    TestBed.tick();
    expect(store.pending()).toBe(true);
    calls[0].resolve({ items: [bookmark('a')] });
    await flushAsync();

    store.reload();
    expect(store.items().map((item) => item.id)).toEqual(['a']);
    expect(store.pending()).toBe(false);

    calls[1].resolve({ items: [bookmark('b')] });
    await flushAsync();
    expect(store.items().map((item) => item.id)).toEqual(['b']);
  });
});
