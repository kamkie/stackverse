import { Component, inject, input, output } from '@angular/core';
import { I18n } from '../i18n/i18n';
import type { Bookmark } from '../api/types';

/**
 * One bookmark as a card `<li>`. Owner or report actions are projected by the
 * parent (wrapped in `.sv-bookmark-actions`), rendered on the meta line.
 */
@Component({
  selector: 'li[app-bookmark-card]',
  host: {
    class: 'sv-card sv-bookmark',
    '[attr.data-ctx]': "'bookmark:' + bookmark().id",
  },
  template: `
    <div class="sv-bookmark-head">
      <h3 class="sv-bookmark-title">
        <a [href]="bookmark().url" target="_blank" rel="noopener noreferrer">
          {{ bookmark().title }}
        </a>
      </h3>
      @if (bookmark().status === 'hidden') {
        <span class="sv-badge sv-badge--warning">{{ t('ui.bookmark.hidden') }}</span>
      }
      @if (bookmark().visibility === 'public') {
        <span class="sv-badge">{{ t('ui.visibility.public') }}</span>
      }
    </div>
    <span class="sv-bookmark-url">{{ bookmark().url }}</span>
    @if (bookmark().notes) {
      <p class="sv-bookmark-notes">{{ bookmark().notes }}</p>
    }
    <div class="sv-bookmark-meta">
      @if (bookmark().tags.length > 0) {
        <ul class="sv-tag-list">
          @for (tag of bookmark().tags; track tag) {
            <li>
              <button
                type="button"
                class="sv-tag"
                [class.is-active]="activeTags().includes(tag)"
                (click)="toggleTag.emit(tag)"
              >
                {{ tag }}
              </button>
            </li>
          }
        </ul>
      }
      <span>{{ bookmark().owner }}</span>
      <time [attr.datetime]="bookmark().createdAt">{{ createdAt() }}</time>
      <ng-content />
    </div>
  `,
})
export class BookmarkCard {
  private readonly i18n = inject(I18n);
  protected readonly t = this.i18n.t;

  readonly bookmark = input.required<Bookmark>();
  readonly activeTags = input<string[]>([]);
  readonly toggleTag = output<string>();

  protected createdAt(): string {
    return new Date(this.bookmark().createdAt).toLocaleDateString(this.i18n.resolvedLanguage());
  }
}
