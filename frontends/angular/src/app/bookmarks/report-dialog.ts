import { Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { fieldErrorFor, isConflict } from '../api/problem';
import type { Bookmark, FieldError, ReportReason } from '../api/types';
import { ToastStore } from '../core/toast';
import { I18n } from '../i18n/i18n';
import { Dialog } from '../shared/dialog';
import { Field } from '../shared/field';
import { BookmarksApi } from './api';

export const REPORT_REASONS: ReportReason[] = ['spam', 'offensive', 'broken-link', 'other'];

@Component({
  selector: 'app-report-dialog',
  imports: [Dialog, Field, FormsModule],
  template: `
    <sv-dialog
      [title]="t('ui.action.report') + ' — ' + bookmark().title"
      [ctx]="'bookmark:' + bookmark().id"
      (closed)="closed.emit()"
    >
      <form class="sv-form" (ngSubmit)="submit()">
        <sv-field
          [label]="t('ui.field.reason')"
          forId="report-reason"
          [error]="fieldError('reason')"
        >
          <select id="report-reason" class="sv-select" name="reason" [(ngModel)]="reason">
            @for (option of reasons; track option) {
              <option [value]="option">{{ t('ui.report.reason.' + option) }}</option>
            }
          </select>
        </sv-field>
        <sv-field
          [label]="t('ui.field.comment')"
          forId="report-comment"
          [error]="fieldError('comment')"
        >
          <textarea
            id="report-comment"
            class="sv-textarea"
            name="comment"
            [(ngModel)]="comment"
            [attr.aria-invalid]="fieldError('comment') ? true : null"
          ></textarea>
        </sv-field>
        <div class="sv-form-actions">
          <button type="button" class="sv-button" (click)="closed.emit()">
            {{ t('ui.action.cancel') }}
          </button>
          <button type="submit" class="sv-button sv-button--primary" [disabled]="pending()">
            {{ t('ui.action.report') }}
          </button>
        </div>
      </form>
    </sv-dialog>
  `,
})
export class ReportDialog {
  protected readonly t = inject(I18n).t;
  private readonly api = inject(BookmarksApi);
  private readonly toast = inject(ToastStore);

  readonly bookmark = input.required<Bookmark>();
  readonly closed = output<void>();
  /**
   * Fires when a submit confirms the reported state — a 201 create or a 409
   * duplicate (SPEC rule 13: proof an open report already exists) — so the
   * caller can mark the card as reported.
   */
  readonly reported = output<string>();

  protected readonly reasons = REPORT_REASONS;
  protected reason: ReportReason = 'spam';
  protected comment = '';

  protected readonly pending = signal(false);
  protected readonly error = signal<unknown>(null);

  protected fieldError(field: string): FieldError | undefined {
    return fieldErrorFor(this.error(), field);
  }

  protected async submit(): Promise<void> {
    this.pending.set(true);
    this.error.set(null);
    try {
      const bookmark = this.bookmark();
      await this.api.reportBookmark(bookmark.id, {
        reason: this.reason,
        ...(this.comment ? { comment: this.comment } : {}),
      });
      this.toast.push(this.t('ui.toast.report-submitted'), 'success');
      this.reported.emit(bookmark.id);
      this.closed.emit();
    } catch (error) {
      // A 409 means this user already has an open report on the bookmark
      // (SPEC rule 13) — positive proof of the reported state, so confirm
      // instead of surfacing an error.
      if (isConflict(error)) {
        this.toast.push(this.t('ui.toast.report-duplicate'), 'success');
        this.reported.emit(this.bookmark().id);
        this.closed.emit();
      } else {
        this.error.set(error);
      }
    } finally {
      this.pending.set(false);
    }
  }
}
