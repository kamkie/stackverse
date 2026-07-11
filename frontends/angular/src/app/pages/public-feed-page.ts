import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { Bookmark } from '../api/types';
import { SessionStore } from '../auth/session';
import { BookmarksApi, type BookmarkFilters } from '../bookmarks/api';
import { BookmarkCard } from '../bookmarks/bookmark-card';
import { BookmarkList } from '../bookmarks/bookmark-list';
import { BookmarkListStore } from '../bookmarks/list-store';
import { ReportDialog } from '../bookmarks/report-dialog';
import { addReportedId, readReportedIds } from '../bookmarks/reported-store';
import { debounced } from '../core/debounce';
import { I18n } from '../i18n/i18n';

/** Anonymous view of public bookmarks, with a report action when authenticated. */
@Component({
  selector: 'app-public-feed-page',
  imports: [BookmarkCard, BookmarkList, FormsModule, ReportDialog],
  template: `
    <section class="sv-content">
      <h1 class="sv-page-title">{{ t('ui.nav.public-feed') }}</h1>
      <div class="sv-toolbar">
        <input
          type="search"
          class="sv-input"
          name="search"
          [placeholder]="t('ui.bookmarks.search.placeholder')"
          [attr.aria-label]="t('ui.bookmarks.search.placeholder')"
          [ngModel]="search()"
          (ngModelChange)="search.set($event)"
        />
      </div>
      <app-bookmark-list
        [store]="list"
        [emptyMessage]="filtered() ? t('ui.bookmarks.no-matches') : undefined"
      >
        <ng-template let-bookmark>
          <li
            app-bookmark-card
            [bookmark]="bookmark"
            [activeTags]="activeTags()"
            (toggleTag)="toggleTag($event)"
          >
            @if (session.authenticated()) {
              <div class="sv-bookmark-actions">
                @if (reportedIds().has(bookmark.id)) {
                  <button type="button" class="sv-button sv-button--ghost sv-button--sm" disabled>
                    {{ t('ui.report.reported') }}
                  </button>
                } @else {
                  <button
                    type="button"
                    class="sv-button sv-button--ghost sv-button--sm"
                    (click)="reporting.set(bookmark)"
                  >
                    {{ t('ui.action.report') }}
                  </button>
                }
              </div>
            }
          </li>
        </ng-template>
      </app-bookmark-list>
      @if (reporting(); as bookmark) {
        <app-report-dialog
          [bookmark]="bookmark"
          (reported)="onReported($event)"
          (closed)="reporting.set(null)"
        />
      }
    </section>
  `,
})
export class PublicFeedPage {
  protected readonly t = inject(I18n).t;
  protected readonly session = inject(SessionStore);
  private readonly api = inject(BookmarksApi);

  protected readonly activeTags = signal<string[]>([]);
  protected readonly search = signal('');
  private readonly q = debounced(this.search, 300);
  private readonly filters = computed<BookmarkFilters>(() => ({
    tags: this.activeTags(),
    q: this.q(),
    visibility: 'public',
  }));

  protected readonly list = new BookmarkListStore(this.api, this.filters);

  protected readonly reporting = signal<Bookmark | null>(null);
  // Browser-session memory of what this visitor already reported (see
  // reported-store): the button flips to a disabled "Reported" to discourage
  // repeat reports. Both submit outcomes that prove the state feed it — a 201
  // create and a 409 duplicate (ReportDialog confirms both).
  protected readonly reportedIds = signal<ReadonlySet<string>>(readReportedIds());

  protected readonly filtered = computed(() => this.q() !== '' || this.activeTags().length > 0);

  protected toggleTag(tag: string): void {
    this.activeTags.update((tags) =>
      tags.includes(tag) ? tags.filter((existing) => existing !== tag) : [...tags, tag],
    );
  }

  protected onReported(bookmarkId: string): void {
    this.reportedIds.set(addReportedId(bookmarkId));
  }
}
