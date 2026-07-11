import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { flushAsync } from '../../testing/bundle-fetch';
import { ApiError } from '../api/problem';
import type { AdminStats } from '../api/types';
import { SessionStore } from '../auth/session';
import { I18n } from '../i18n/i18n';
import { AdminApi } from './api';
import { DashboardPage } from './dashboard-page';

const STATS: AdminStats = {
  totals: {
    users: 4,
    bookmarks: 8,
    publicBookmarks: 3,
    hiddenBookmarks: 1,
    openReports: 2,
  },
  daily: [
    { date: '2026-07-01', bookmarksCreated: 1, activeUsers: 2 },
    { date: '2026-07-02', bookmarksCreated: 4, activeUsers: 3 },
  ],
  topTags: [{ tag: 'angular', count: 5 }],
};

describe('DashboardPage', () => {
  let fixture: ComponentFixture<DashboardPage>;
  let getStats: ReturnType<typeof vi.fn>;

  async function render(result: AdminStats | Error): Promise<void> {
    getStats =
      result instanceof Error
        ? vi.fn().mockRejectedValue(result)
        : vi.fn().mockResolvedValue(result);
    await TestBed.configureTestingModule({
      imports: [DashboardPage],
      providers: [
        provideRouter([]),
        { provide: AdminApi, useValue: { getStats } },
        {
          provide: I18n,
          useValue: {
            t: (key: string) => key,
            tCount: (key: string, count: number) => `${key}:${count}`,
          },
        },
        { provide: SessionStore, useValue: { refresh: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(DashboardPage);
    fixture.detectChanges();
    await flushAsync();
    fixture.detectChanges();
  }

  it('renders contract totals, moderation navigation, chart geometry, and top tags', async () => {
    await render(STATS);

    expect(getStats).toHaveBeenCalledOnce();
    const values = Array.from(
      fixture.nativeElement.querySelectorAll('.sv-stat-value') as NodeListOf<HTMLElement>,
    ).map((element) => element.textContent?.trim());
    expect(values).toEqual(['4', '8', '3', '1', '2']);
    expect(
      (fixture.nativeElement.querySelector('a.sv-stat--link') as HTMLAnchorElement).getAttribute(
        'href',
      ),
    ).toBe('/admin/reports');
    expect(fixture.nativeElement.querySelectorAll('svg rect')).toHaveLength(4);
    expect(fixture.nativeElement.querySelector('svg title')?.textContent).toContain(
      '2026-07-01: 1 ui.admin.stats.bookmarks-created, 2 ui.admin.stats.active-users',
    );
    expect(fixture.nativeElement.querySelector('.sv-tag')?.textContent).toContain('angular');
    expect(fixture.nativeElement.querySelector('.sv-chart-label')?.textContent?.trim()).toBe('4');
  });

  it('renders a dependency failure as an error state', async () => {
    await render(new ApiError(503, { title: 'Unavailable', detail: 'Stats are unavailable.' }));

    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent?.trim()).toBe(
      'Stats are unavailable.',
    );
    expect(fixture.nativeElement.querySelector('.sv-stats-grid')).toBeNull();
  });
});
