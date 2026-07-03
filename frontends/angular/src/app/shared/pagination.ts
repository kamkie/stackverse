import { Component, inject, input, output } from '@angular/core';
import { I18n } from '../i18n/i18n';

/** Prev/next control for the offset-paginated lists. */
@Component({
  selector: 'sv-pagination',
  template: `
    @if (totalPages() > 1) {
      <nav class="sv-pagination">
        <button
          type="button"
          class="sv-button sv-button--ghost sv-button--sm"
          [disabled]="page() <= 0"
          [attr.aria-label]="t('ui.action.previous')"
          (click)="paged.emit(page() - 1)"
        >
          ‹
        </button>
        <span>{{ page() + 1 }} / {{ totalPages() }}</span>
        <button
          type="button"
          class="sv-button sv-button--ghost sv-button--sm"
          [disabled]="page() >= totalPages() - 1"
          [attr.aria-label]="t('ui.action.next')"
          (click)="paged.emit(page() + 1)"
        >
          ›
        </button>
      </nav>
    }
  `,
})
export class Pagination {
  protected readonly t = inject(I18n).t;

  readonly page = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly paged = output<number>();
}
