import { computed, signal } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { flushAsync } from '../../testing/bundle-fetch';
import { ApiError } from '../api/problem';
import type { Bookmark, BookmarkCursorPage } from '../api/types';
import { SessionStore } from '../auth/session';
import { BookmarksApi, TagsStore } from '../bookmarks/api';
import { ToastStore } from '../core/toast';
import { I18n } from '../i18n/i18n';
import { MyBookmarksPage } from './my-bookmarks-page';

const BOOKMARK: Bookmark = {
  id: 'bookmark-1',
  owner: 'demo',
  url: 'https://example.com/angular',
  title: 'Angular bookmark',
  notes: 'Signals and standalone components',
  tags: ['angular'],
  visibility: 'public',
  status: 'hidden',
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-01T00:00:00Z',
};

const PAGE: BookmarkCursorPage = { items: [BOOKMARK] };

describe('MyBookmarksPage', () => {
  let fixture: ComponentFixture<MyBookmarksPage>;
  let sessionState: ReturnType<typeof signal<'pending' | 'anonymous' | 'authenticated'>>;
  let listBookmarks: ReturnType<typeof vi.fn>;
  let deleteBookmark: ReturnType<typeof vi.fn>;
  let reloadTags: ReturnType<typeof vi.fn>;
  let refreshSession: ReturnType<typeof vi.fn>;
  let toast: ToastStore;

  async function render(
    initial: 'pending' | 'anonymous' | 'authenticated',
    deleteError?: Error,
  ): Promise<void> {
    sessionState = signal(initial);
    listBookmarks = vi.fn().mockResolvedValue(PAGE);
    deleteBookmark = deleteError
      ? vi.fn().mockRejectedValue(deleteError)
      : vi.fn().mockResolvedValue(undefined);
    reloadTags = vi.fn();
    refreshSession = vi.fn().mockResolvedValue(undefined);
    await TestBed.configureTestingModule({
      imports: [MyBookmarksPage],
      providers: [
        {
          provide: SessionStore,
          useValue: {
            pending: computed(() => sessionState() === 'pending'),
            authenticated: computed(() => sessionState() === 'authenticated'),
            refresh: refreshSession,
          },
        },
        { provide: BookmarksApi, useValue: { listBookmarks, deleteBookmark } },
        {
          provide: TagsStore,
          useValue: {
            tags: signal({ tags: [{ tag: 'angular', count: 1 }] }),
            reload: reloadTags,
          },
        },
        {
          provide: I18n,
          useValue: { t: (key: string) => key, resolvedLanguage: () => 'en' },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(MyBookmarksPage);
    toast = TestBed.inject(ToastStore);
    fixture.detectChanges();
    TestBed.tick();
    await flushAsync();
    fixture.detectChanges();
  }

  function button(label: string, root: ParentNode = fixture.nativeElement): HTMLButtonElement {
    const found = Array.from(root.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(
      (candidate) => candidate.textContent?.trim() === label,
    );
    if (!found) throw new Error(`Missing button ${label}`);
    return found;
  }

  it('does not call the owner endpoint until the gateway session is authenticated', async () => {
    await render('pending');
    expect(fixture.nativeElement.querySelector('sv-loading')).not.toBeNull();
    expect(listBookmarks).not.toHaveBeenCalled();

    sessionState.set('anonymous');
    fixture.detectChanges();
    TestBed.tick();
    expect(fixture.nativeElement.querySelector('sv-login-prompt')).not.toBeNull();
    expect(refreshSession).toHaveBeenCalledOnce();
    expect(listBookmarks).not.toHaveBeenCalled();

    sessionState.set('authenticated');
    fixture.detectChanges();
    TestBed.tick();
    await flushAsync();
    fixture.detectChanges();
    expect(listBookmarks).toHaveBeenCalledWith({ tags: [], q: '' });
    expect(fixture.nativeElement.textContent).toContain('Angular bookmark');
  });

  it('renders owner metadata and re-queries when a tag filter is toggled', async () => {
    await render('authenticated');

    const card = fixture.nativeElement.querySelector(
      'li[data-ctx="bookmark:bookmark-1"]',
    ) as HTMLLIElement;
    expect(card.textContent).toContain('ui.bookmark.hidden');
    expect(card.textContent).toContain('ui.visibility.public');
    button('angular', card).click();
    fixture.detectChanges();
    TestBed.tick();
    await flushAsync();

    expect(listBookmarks).toHaveBeenLastCalledWith({ tags: ['angular'], q: '' });
  });

  it('deletes a bookmark, refreshes list and tags, and closes confirmation', async () => {
    await render('authenticated');
    const card = fixture.nativeElement.querySelector(
      'li[data-ctx="bookmark:bookmark-1"]',
    ) as HTMLLIElement;
    button('ui.action.delete', card).click();
    fixture.detectChanges();
    button(
      'ui.action.delete',
      fixture.nativeElement.querySelector('sv-confirm-dialog') as HTMLElement,
    ).click();
    await flushAsync();
    fixture.detectChanges();

    expect(deleteBookmark).toHaveBeenCalledWith('bookmark-1');
    expect(listBookmarks).toHaveBeenCalledTimes(2);
    expect(reloadTags).toHaveBeenCalledOnce();
    expect(toast.items().at(-1)?.message).toBe('ui.toast.bookmark-deleted');
    expect(fixture.nativeElement.querySelector('sv-confirm-dialog')).toBeNull();
  });

  it('keeps confirmation open and reports a failed delete without refreshing state', async () => {
    await render(
      'authenticated',
      new ApiError(503, { title: 'Unavailable', detail: 'Bookmark service unavailable.' }),
    );
    const card = fixture.nativeElement.querySelector(
      'li[data-ctx="bookmark:bookmark-1"]',
    ) as HTMLLIElement;
    button('ui.action.delete', card).click();
    fixture.detectChanges();
    button(
      'ui.action.delete',
      fixture.nativeElement.querySelector('sv-confirm-dialog') as HTMLElement,
    ).click();
    await flushAsync();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('sv-confirm-dialog')).not.toBeNull();
    expect(listBookmarks).toHaveBeenCalledOnce();
    expect(reloadTags).not.toHaveBeenCalled();
    expect(toast.items().at(-1)).toMatchObject({
      message: 'Bookmark service unavailable.',
      variant: 'danger',
    });
  });
});
