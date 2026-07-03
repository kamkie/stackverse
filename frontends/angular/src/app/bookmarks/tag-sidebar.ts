import { Component, inject, input, output } from '@angular/core';
import { I18n } from '../i18n/i18n';
import { TagsStore } from './api';

/** The caller's tags with usage counts (`GET /api/v1/tags`); click to filter. */
@Component({
  selector: 'app-tag-sidebar',
  host: { class: 'sv-sidebar', role: 'complementary' },
  template: `
    <h2 class="sv-sidebar-title">{{ t('ui.nav.tags') }}</h2>
    @if (tags.tags(); as list) {
      @if (list.tags.length > 0) {
        <ul class="sv-tag-list">
          @for (entry of list.tags; track entry.tag) {
            <li>
              <button
                type="button"
                class="sv-tag"
                [class.is-active]="activeTags().includes(entry.tag)"
                (click)="toggleTag.emit(entry.tag)"
              >
                {{ entry.tag }} <span class="sv-tag-count">{{ entry.count }}</span>
              </button>
            </li>
          }
        </ul>
      } @else {
        <span class="sv-field-hint">—</span>
      }
    } @else {
      <span class="sv-field-hint">—</span>
    }
  `,
})
export class TagSidebar {
  protected readonly t = inject(I18n).t;
  protected readonly tags = inject(TagsStore);

  readonly activeTags = input.required<string[]>();
  readonly toggleTag = output<string>();
}
