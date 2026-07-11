import { signal } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { flushAsync } from '../../testing/bundle-fetch';
import type { Bookmark, BookmarkCursorPage, Report } from '../api/types';
import { SessionStore } from '../auth/session';
import { BookmarksApi } from '../bookmarks/api';
import { addReportedId, readReportedIds } from '../bookmarks/reported-store';
import { ToastStore } from '../core/toast';
import { I18n } from '../i18n/i18n';
import { PublicFeedPage } from './public-feed-page';

const BOOKMARK: Bookmark = {
  id: 'bookmark-1',
  owner: 'author',
  url: 'https://example.com/public',
  title: 'Public bookmark',
  tags: ['public'],
  visibility: 'public',
  status: 'active',
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-01T00:00:00Z',
};

const PAGE: BookmarkCursorPage = { items: [BOOKMARK] };
const REPORT: Report = {
  id: 'report-1',
  bookmarkId: BOOKMARK.id,
  reporter: 'demo',
  reason: 'spam',
  status: 'open',
  createdAt: '2026-07-02T00:00:00Z',
};

describe('PublicFeedPage', () => {
  let fixture: ComponentFixture<PublicFeedPage>;
  let authenticated: ReturnType<typeof signal<boolean>>;
  let listBookmarks: ReturnType<typeof vi.fn>;
  let reportBookmark: ReturnType<typeof vi.fn>;
  let toast: ToastStore;

  async function render(isAuthenticated: boolean): Promise<void> {
    sessionStorage.clear();
    authenticated = signal(isAuthenticated);
    listBookmarks = vi.fn().mockResolvedValue(PAGE);
    reportBookmark = vi.fn().mockResolvedValue(REPORT);
    await TestBed.configureTestingModule({
      imports: [PublicFeedPage],
      providers: [
        { provide: SessionStore, useValue: { authenticated } },
        { provide: BookmarksApi, useValue: { listBookmarks, reportBookmark } },
        {
          provide: I18n,
          useValue: { t: (key: string) => key, resolvedLanguage: () => 'en' },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PublicFeedPage);
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

  it('always requests the anonymous public surface and hides reporting while logged out', async () => {
    await render(false);

    expect(listBookmarks).toHaveBeenCalledWith({ tags: [], q: '', visibility: 'public' });
    expect(fixture.nativeElement.textContent).toContain('Public bookmark');
    expect(fixture.nativeElement.textContent).not.toContain('ui.action.report');

    button('public').click();
    fixture.detectChanges();
    TestBed.tick();
    await flushAsync();
    expect(listBookmarks).toHaveBeenLastCalledWith({
      tags: ['public'],
      q: '',
      visibility: 'public',
    });
  });

  it('submits a report and persists the reported marker for this browser session', async () => {
    await render(true);
    button('ui.action.report').click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    await flushAsync();
    fixture.detectChanges();

    expect(reportBookmark).toHaveBeenCalledWith('bookmark-1', { reason: 'spam' });
    expect(readReportedIds().has('bookmark-1')).toBe(true);
    expect(button('ui.report.reported').disabled).toBe(true);
    expect(toast.items().at(-1)?.message).toBe('ui.toast.report-submitted');
  });

  it('restores the disabled reported state from session storage', async () => {
    await render(true);
    addReportedId('bookmark-1');

    // The page reads storage at construction, so remount it as a navigation would.
    fixture.destroy();
    fixture = TestBed.createComponent(PublicFeedPage);
    fixture.detectChanges();
    TestBed.tick();
    await flushAsync();
    fixture.detectChanges();

    expect(button('ui.report.reported').disabled).toBe(true);
    expect(fixture.nativeElement.textContent).not.toContain('ui.action.report');
  });
});
