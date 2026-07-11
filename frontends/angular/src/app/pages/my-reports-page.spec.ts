import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { flushAsync } from '../../testing/bundle-fetch';
import { ApiError } from '../api/problem';
import type { Bookmark, Page, Report } from '../api/types';
import { SessionStore } from '../auth/session';
import { BookmarksApi } from '../bookmarks/api';
import { addReportedId, readReportedIds } from '../bookmarks/reported-store';
import { ToastStore } from '../core/toast';
import { I18n } from '../i18n/i18n';
import { MyReportsPage } from './my-reports-page';

const BOOKMARK: Bookmark = {
  id: 'bookmark-1',
  owner: 'author',
  url: 'https://example.com/reported',
  title: 'Reported bookmark',
  tags: [],
  visibility: 'public',
  status: 'active',
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-01T00:00:00Z',
};

const OPEN_REPORT: Report = {
  id: 'open-report',
  bookmarkId: BOOKMARK.id,
  reporter: 'demo',
  reason: 'spam',
  comment: 'Original comment',
  status: 'open',
  createdAt: '2026-07-02T00:00:00Z',
};

const RESOLVED_REPORT: Report = {
  ...OPEN_REPORT,
  id: 'resolved-report',
  status: 'dismissed',
  resolvedBy: 'moderator',
  resolvedAt: '2026-07-03T00:00:00Z',
  resolutionNote: 'No violation',
};

const PAGE: Page<Report> = {
  items: [OPEN_REPORT, RESOLVED_REPORT],
  page: 0,
  size: 20,
  totalItems: 2,
  totalPages: 1,
};

describe('MyReportsPage', () => {
  let fixture: ComponentFixture<MyReportsPage>;
  let listMyReports: ReturnType<typeof vi.fn>;
  let updateMyReport: ReturnType<typeof vi.fn>;
  let withdrawReport: ReturnType<typeof vi.fn>;
  let toast: ToastStore;

  async function render(
    options: { updateError?: Error; withdrawError?: Error } = {},
  ): Promise<void> {
    sessionStorage.clear();
    listMyReports = vi.fn().mockResolvedValue(PAGE);
    updateMyReport = options.updateError
      ? vi.fn().mockRejectedValue(options.updateError)
      : vi.fn().mockResolvedValue({ ...OPEN_REPORT, reason: 'offensive' });
    withdrawReport = options.withdrawError
      ? vi.fn().mockRejectedValue(options.withdrawError)
      : vi.fn().mockResolvedValue(undefined);
    await TestBed.configureTestingModule({
      imports: [MyReportsPage],
      providers: [
        {
          provide: BookmarksApi,
          useValue: {
            listMyReports,
            updateMyReport,
            withdrawReport,
            getBookmark: vi.fn().mockResolvedValue(BOOKMARK),
          },
        },
        {
          provide: I18n,
          useValue: {
            t: (key: string) =>
              key.startsWith('validation.') ? key.slice(key.lastIndexOf('.') + 1) : key,
            resolvedLanguage: () => 'en',
          },
        },
        { provide: SessionStore, useValue: { refresh: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(MyReportsPage);
    toast = TestBed.inject(ToastStore);
    fixture.detectChanges();
    await flushAsync();
    fixture.detectChanges();
    await flushAsync(); // BookmarkCell lookup resolves after the report page
    fixture.detectChanges();
  }

  function row(id: string): HTMLTableRowElement {
    return fixture.nativeElement.querySelector(
      `tr[data-ctx="report:${id}"]`,
    ) as HTMLTableRowElement;
  }

  function button(label: string, root: ParentNode = fixture.nativeElement): HTMLButtonElement {
    const found = Array.from(root.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(
      (candidate) => candidate.textContent?.trim() === label,
    );
    if (!found) throw new Error(`Missing button ${label}`);
    return found;
  }

  function submitEdit(): void {
    (fixture.nativeElement.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
  }

  it('keeps reporter history visible but exposes mutation controls only for open reports', async () => {
    await render();

    expect(listMyReports).toHaveBeenCalledWith('', 0);
    expect(button('ui.action.edit', row('open-report'))).toBeDefined();
    expect(button('ui.action.withdraw', row('open-report'))).toBeDefined();
    expect(row('resolved-report').querySelectorAll('button')).toHaveLength(0);
    expect(row('resolved-report').textContent).toContain('No violation');
    expect(fixture.nativeElement.textContent).toContain('Reported bookmark');

    const select = fixture.nativeElement.querySelector(
      'select[name="status"]',
    ) as HTMLSelectElement;
    select.value = 'actioned';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    TestBed.tick();
    await flushAsync();
    expect(listMyReports).toHaveBeenLastCalledWith('actioned', 0);
  });

  it('edits an open report with the contract payload and reloads reporter history', async () => {
    await render();
    button('ui.action.edit', row('open-report')).click();
    fixture.detectChanges();
    await flushAsync();
    fixture.detectChanges();

    const reason = fixture.nativeElement.querySelector(
      'select[name="reason"]',
    ) as HTMLSelectElement;
    reason.value = 'offensive';
    reason.dispatchEvent(new Event('change', { bubbles: true }));
    const comment = fixture.nativeElement.querySelector(
      'textarea[name="comment"]',
    ) as HTMLTextAreaElement;
    comment.value = 'Updated comment';
    comment.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    submitEdit();
    await flushAsync();

    expect(updateMyReport).toHaveBeenCalledWith('open-report', {
      reason: 'offensive',
      comment: 'Updated comment',
    });
    expect(listMyReports).toHaveBeenCalledTimes(2);
    expect(toast.items().at(-1)?.message).toBe('ui.toast.report-updated');
    expect(fixture.nativeElement.querySelector('app-edit-report-dialog')).toBeNull();
  });

  it('maps validation and concurrent-resolution conflicts inside the edit dialog', async () => {
    await render({
      updateError: new ApiError(400, {
        title: 'Validation failed',
        errors: [
          {
            field: 'comment',
            messageKey: 'validation.report.comment.too-long',
            message: 'Comment is too long.',
          },
        ],
      }),
    });
    button('ui.action.edit', row('open-report')).click();
    fixture.detectChanges();
    await flushAsync();
    fixture.detectChanges();
    submitEdit();
    await flushAsync();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.sv-field-error')?.textContent?.trim()).toBe(
      'Comment is too long.',
    );
    expect(fixture.nativeElement.querySelector('app-edit-report-dialog')).not.toBeNull();

    updateMyReport.mockRejectedValueOnce(
      new ApiError(409, { title: 'Conflict', detail: 'The report is no longer open.' }),
    );
    submitEdit();
    await flushAsync();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent?.trim()).toBe(
      'The report is no longer open.',
    );
  });

  it('withdraws an open report and clears the feed session marker', async () => {
    await render();
    addReportedId(BOOKMARK.id);
    button('ui.action.withdraw', row('open-report')).click();
    fixture.detectChanges();
    button(
      'ui.action.withdraw',
      fixture.nativeElement.querySelector('sv-confirm-dialog') as HTMLElement,
    ).click();
    await flushAsync();
    fixture.detectChanges();

    expect(withdrawReport).toHaveBeenCalledWith('open-report');
    expect(readReportedIds().has(BOOKMARK.id)).toBe(false);
    expect(listMyReports).toHaveBeenCalledTimes(2);
    expect(toast.items().at(-1)?.message).toBe('ui.toast.report-withdrawn');
    expect(fixture.nativeElement.querySelector('sv-confirm-dialog')).toBeNull();
  });

  it('retains the marker and confirmation when withdrawal fails', async () => {
    await render({
      withdrawError: new ApiError(503, {
        title: 'Unavailable',
        detail: 'Report service unavailable.',
      }),
    });
    addReportedId(BOOKMARK.id);
    button('ui.action.withdraw', row('open-report')).click();
    fixture.detectChanges();
    button(
      'ui.action.withdraw',
      fixture.nativeElement.querySelector('sv-confirm-dialog') as HTMLElement,
    ).click();
    await flushAsync();
    fixture.detectChanges();

    expect(readReportedIds().has(BOOKMARK.id)).toBe(true);
    expect(fixture.nativeElement.querySelector('sv-confirm-dialog')).not.toBeNull();
    expect(toast.items().at(-1)).toMatchObject({
      message: 'Report service unavailable.',
      variant: 'danger',
    });
    expect(listMyReports).toHaveBeenCalledOnce();
  });
});
