import { NgTemplateOutlet } from '@angular/common';
import { Component, contentChild, inject, input, TemplateRef } from '@angular/core';
import type { Bookmark } from '../api/types';
import { I18n } from '../i18n/i18n';
import { ErrorState, Loading } from '../shared/states';
import type { BookmarkListStore } from './list-store';

/**
 * Cursor-paginated list with the "load more" UX of `GET /api/v2/bookmarks`.
 * The caller projects an `<ng-template let-bookmark>` rendering one card.
 */
@Component({
  selector: 'app-bookmark-list',
  imports: [NgTemplateOutlet, ErrorState, Loading],
  template: `
    @if (store().pending()) {
      <sv-loading />
    } @else if (store().error() !== null) {
      <sv-error-state [error]="store().error()" />
    } @else if (store().items().length === 0) {
      <div class="sv-empty">{{ emptyMessage() ?? t('ui.bookmarks.empty') }}</div>
    } @else {
      <ul class="sv-card-list">
        @for (bookmark of store().items(); track bookmark.id) {
          <ng-container
            *ngTemplateOutlet="itemTemplate(); context: { $implicit: bookmark }"
          />
        }
      </ul>
      @if (store().hasMore()) {
        <div class="sv-load-more">
          <button
            type="button"
            class="sv-button"
            [disabled]="store().fetchingMore()"
            (click)="store().loadMore()"
          >
            {{ t('ui.action.load-more') }}
          </button>
        </div>
      }
    }
  `,
})
export class BookmarkList {
  protected readonly t = inject(I18n).t;

  readonly store = input.required<BookmarkListStore>();
  /** Shown when the list is empty; defaults to the "no bookmarks yet" message. */
  readonly emptyMessage = input<string>();

  protected readonly itemTemplate =
    contentChild.required<TemplateRef<{ $implicit: Bookmark }>>(TemplateRef);
}
