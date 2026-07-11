import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { flushAsync } from '../../testing/bundle-fetch';
import { ApiError } from '../api/problem';
import type { Bookmark, Page, Report, ReportStatus } from '../api/types';
import { SessionStore } from '../auth/session';
import { BookmarksApi } from '../bookmarks/api';
import { ToastStore } from '../core/toast';
import { I18n } from '../i18n/i18n';
import { AdminApi } from './api';
import { ReportsPage } from './reports-page';

const BOOKMARK: Bookmark = {
  id: 'bookmark-1',
  owner: 'demo',
  url: 'https://example.com/angular',
  title: 'Angular',
  tags: ['angular'],
  visibility: 'public',
  status: 'active',
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-01T00:00:00Z',
};

function report(status: ReportStatus = 'open'): Report {
  return {
    id: 'report-1',
    bookmarkId: BOOKMARK.id,
    reporter: 'reporter',
    reason: 'spam',
    comment: 'Suspicious link',
    status,
    createdAt: '2026-07-02T00:00:00Z',
    ...(status === 'open'
      ? {}
      : {
          resolvedBy: 'moderator',
          resolvedAt: '2026-07-03T00:00:00Z',
          resolutionNote: 'Reviewed',
        }),
  };
}

function page(item: Report): Page<Report> {
  return { items: [item], page: 0, size: 20, totalItems: 1, totalPages: 1 };
}

describe('ReportsPage', () => {
  let fixture: ComponentFixture<ReportsPage>;
  let listReports: ReturnType<typeof vi.fn>;
  let resolveReport: ReturnType<typeof vi.fn>;
  let toast: ToastStore;

  async function render(item: Report, resolutionError?: Error): Promise<void> {
    listReports = vi.fn().mockResolvedValue(page(item));
    resolveReport = resolutionError
      ? vi.fn().mockRejectedValue(resolutionError)
      : vi.fn().mockResolvedValue({ ...item, status: 'dismissed' });
    await TestBed.configureTestingModule({
      imports: [ReportsPage],
      providers: [
        { provide: AdminApi, useValue: { listReports, resolveReport } },
        { provide: BookmarksApi, useValue: { getBookmark: vi.fn().mockResolvedValue(BOOKMARK) } },
        {
          provide: I18n,
          useValue: { t: (key: string) => key, resolvedLanguage: () => 'en' },
        },
        { provide: SessionStore, useValue: { refresh: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ReportsPage);
    toast = TestBed.inject(ToastStore);
    fixture.detectChanges();
    await flushAsync();
    fixture.detectChanges();
    await flushAsync(); // BookmarkCell lookup resolves after the report page
    fixture.detectChanges();
  }

  function button(label: string): HTMLButtonElement {
    const found = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((candidate) => candidate.textContent?.trim() === label);
    if (!found) throw new Error(`Missing button ${label}`);
    return found;
  }

  it('loads the open queue and dismisses a report through the moderator endpoint', async () => {
    await render(report());

    expect(listReports).toHaveBeenCalledWith('open', 0);
    expect(fixture.nativeElement.querySelector('tr[data-ctx="report:report-1"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Angular');

    button('ui.action.dismiss').click();
    await flushAsync();

    expect(resolveReport).toHaveBeenCalledWith('report-1', { resolution: 'dismissed' });
    expect(listReports).toHaveBeenCalledTimes(2); // mutation reloads the queue
  });

  it('supports revising an actioned decision and re-opening it', async () => {
    await render(report('actioned'));

    button('ui.action.dismiss').click();
    await flushAsync();
    expect(resolveReport).toHaveBeenLastCalledWith('report-1', { resolution: 'dismissed' });

    button('ui.action.reopen').click();
    await flushAsync();

    expect(resolveReport).toHaveBeenLastCalledWith('report-1', { resolution: 'open' });
  });

  it('resets pagination when the queue status changes', async () => {
    await render(report());
    const select = fixture.nativeElement.querySelector(
      'select[name="status"]',
    ) as HTMLSelectElement;
    select.value = 'actioned';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    TestBed.tick();
    await flushAsync();

    expect(listReports).toHaveBeenLastCalledWith('actioned', 0);
  });

  it('surfaces failed moderation without leaving the controls disabled', async () => {
    await render(
      report(),
      new ApiError(503, { title: 'Unavailable', detail: 'Moderation is temporarily unavailable.' }),
    );

    button('ui.action.action').click();
    await flushAsync();
    fixture.detectChanges();

    expect(resolveReport).toHaveBeenCalledWith('report-1', { resolution: 'actioned' });
    expect(toast.items()).toEqual([
      {
        id: 0,
        message: 'Moderation is temporarily unavailable.',
        variant: 'danger',
      },
    ]);
    expect(button('ui.action.action').disabled).toBe(false);
  });
});
