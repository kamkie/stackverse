import { Component, effect, inject, Injectable, input, signal, untracked } from '@angular/core';
import type { Bookmark } from '../api/types';
import { I18n } from '../i18n/i18n';
import { BookmarksApi } from './api';

/**
 * Session-scoped cache for report-context lookups, so a page of report rows
 * asks for each bookmark once. Failures are not cached — a bookmark that
 * becomes readable later gets another chance on the next mount.
 */
@Injectable({ providedIn: 'root' })
export class BookmarkLookup {
  private readonly api = inject(BookmarksApi);
  private readonly cache = new Map<string, Promise<Bookmark>>();

  get(id: string): Promise<Bookmark> {
    let promise = this.cache.get(id);
    if (!promise) {
      promise = this.api.getBookmark(id);
      promise.catch(() => this.cache.delete(id));
      this.cache.set(id, promise);
    }
    return promise;
  }
}

/**
 * Context for a reported bookmark. The read endpoint is owner-or-public-only,
 * so a `404` is an expected state (private, hidden, or already deleted —
 * moderators get no special access); those rows keep the raw id plus a hint.
 */
@Component({
  selector: 'app-bookmark-cell',
  template: `
    @if (bookmark(); as found) {
      <strong>{{ found.title }}</strong>
      <div>
        <a class="sv-bookmark-url" [href]="found.url" target="_blank" rel="noreferrer">
          {{ found.url }}
        </a>
      </div>
    } @else {
      <span class="sv-cell-mono">{{ bookmarkId() }}</span>
      @if (failed()) {
        <div class="sv-field-hint">{{ t('ui.reports.bookmark-unavailable') }}</div>
      }
    }
  `,
})
export class BookmarkCell {
  protected readonly t = inject(I18n).t;
  private readonly lookup = inject(BookmarkLookup);

  readonly bookmarkId = input.required<string>();

  protected readonly bookmark = signal<Bookmark | undefined>(undefined);
  protected readonly failed = signal(false);

  constructor() {
    effect(() => {
      const id = this.bookmarkId();
      untracked(() => {
        this.bookmark.set(undefined);
        this.failed.set(false);
        this.lookup.get(id).then(
          (bookmark) => {
            if (this.bookmarkId() === id) this.bookmark.set(bookmark);
          },
          () => {
            if (this.bookmarkId() === id) this.failed.set(true);
          },
        );
      });
    });
  }
}
