import { Component, input, output } from '@angular/core';
import { Dialog } from './dialog';

/**
 * Confirmation modal for destructive actions. All strings arrive already
 * localized — the caller owns the message keys.
 */
@Component({
  selector: 'sv-confirm-dialog',
  imports: [Dialog],
  template: `
    <sv-dialog [title]="title()" [ctx]="ctx()" (closed)="closed.emit()">
      <p>{{ body() }}</p>
      <div class="sv-form-actions">
        <button type="button" class="sv-button" (click)="closed.emit()">
          {{ cancelLabel() }}
        </button>
        <button
          type="button"
          class="sv-button sv-button--danger"
          [disabled]="pending()"
          (click)="confirmed.emit()"
        >
          {{ confirmLabel() }}
        </button>
      </div>
    </sv-dialog>
  `,
})
export class ConfirmDialog {
  readonly title = input.required<string>();
  readonly body = input.required<string>();
  readonly confirmLabel = input.required<string>();
  readonly cancelLabel = input.required<string>();
  readonly pending = input(false);
  /** Entity being confirmed, as `<type>:<id>` — see Dialog's ctx input. */
  readonly ctx = input<string>();
  readonly confirmed = output<void>();
  readonly closed = output<void>();
}
