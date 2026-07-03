import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { fieldErrorFor, isConflict, messageOf } from '../api/problem';
import type { FieldError, Page, Report, ReportReason, ReportStatus } from '../api/types';
import { BookmarksApi } from '../bookmarks/api';
import { BookmarkCell } from '../bookmarks/bookmark-cell';
import { REPORT_REASONS } from '../bookmarks/report-dialog';
import { removeReportedId } from '../bookmarks/reported-store';
import { ToastStore } from '../core/toast';
import { Query } from '../core/query';
import { I18n } from '../i18n/i18n';
import { ConfirmDialog } from '../shared/confirm-dialog';
import { Dialog } from '../shared/dialog';
import { Field } from '../shared/field';
import { Pagination } from '../shared/pagination';
import { ErrorState, Loading } from '../shared/states';

const STATUSES: ReportStatus[] = ['open', 'dismissed', 'actioned'];

@Component({
  selector: 'app-edit-report-dialog',
  imports: [Dialog, Field, FormsModule],
  template: `
    <sv-dialog
      [title]="t('ui.my-reports.dialog.edit')"
      [ctx]="'report:' + report().id"
      (closed)="closed.emit()"
    >
      <form class="sv-form" (ngSubmit)="submit()">
        <sv-field [label]="t('ui.field.reason')" forId="edit-report-reason" [error]="fieldError('reason')">
          <select id="edit-report-reason" class="sv-select" name="reason" [(ngModel)]="reason">
            @for (option of reasons; track option) {
              <option [value]="option">{{ t('ui.report.reason.' + option) }}</option>
            }
          </select>
        </sv-field>
        <sv-field
          [label]="t('ui.field.comment')"
          forId="edit-report-comment"
          [error]="fieldError('comment')"
        >
          <textarea
            id="edit-report-comment"
            class="sv-textarea"
            name="comment"
            [(ngModel)]="comment"
            [attr.aria-invalid]="fieldError('comment') ? true : null"
          ></textarea>
        </sv-field>
        @if (conflict()) {
          <div class="sv-alert sv-alert--warning" role="alert">{{ errorMessage() }}</div>
        }
        <div class="sv-form-actions">
          <button type="button" class="sv-button" (click)="closed.emit()">
            {{ t('ui.action.cancel') }}
          </button>
          <button type="submit" class="sv-button sv-button--primary" [disabled]="pending()">
            {{ t('ui.action.save') }}
          </button>
        </div>
      </form>
    </sv-dialog>
  `,
})
export class EditReportDialog {
  protected readonly t = inject(I18n).t;
  private readonly api = inject(BookmarksApi);
  private readonly toast = inject(ToastStore);

  readonly report = input.required<Report>();
  readonly closed = output<void>();
  readonly saved = output<void>();

  protected readonly reasons = REPORT_REASONS;
  protected reason: ReportReason = 'spam';
  protected comment = '';

  protected readonly pending = signal(false);
  protected readonly error = signal<unknown>(null);

  ngOnInit(): void {
    this.reason = this.report().reason;
    this.comment = this.report().comment ?? '';
  }

  protected fieldError(field: string): FieldError | undefined {
    return fieldErrorFor(this.error(), field);
  }

  protected conflict(): boolean {
    return isConflict(this.error());
  }

  protected errorMessage(): string {
    return messageOf(this.error());
  }

  protected async submit(): Promise<void> {
    this.pending.set(true);
    this.error.set(null);
    try {
      await this.api.updateMyReport(this.report().id, {
        reason: this.reason,
        ...(this.comment ? { comment: this.comment } : {}),
      });
      this.toast.push(this.t('ui.toast.report-updated'));
      this.saved.emit();
      this.closed.emit();
    } catch (error) {
      this.error.set(error);
    } finally {
      this.pending.set(false);
    }
  }
}

/**
 * The caller's own reports (SPEC rule 13): status is moderation's answer, and
 * open reports can still be revised or withdrawn.
 */
@Component({
  selector: 'app-my-reports-page',
  imports: [
    BookmarkCell,
    ConfirmDialog,
    EditReportDialog,
    ErrorState,
    FormsModule,
    Loading,
    Pagination,
  ],
  template: `
    @if (reports.pending()) {
      <sv-loading />
    } @else if (reports.error() !== null) {
      <sv-error-state [error]="reports.error()" />
    } @else if (reports.data(); as page) {
      <h1 class="sv-page-title">{{ t('ui.nav.my-reports') }}</h1>
      <div class="sv-toolbar">
        <select
          class="sv-select"
          name="status"
          [attr.aria-label]="t('ui.field.status')"
          [ngModel]="status()"
          (ngModelChange)="setStatus($event)"
        >
          <option value="">{{ t('ui.my-reports.filter.all-statuses') }}</option>
          @for (option of statuses; track option) {
            <option [value]="option">{{ t('ui.report.status.' + option) }}</option>
          }
        </select>
      </div>
      @if (page.items.length === 0) {
        <div class="sv-empty">{{ t('ui.my-reports.empty') }}</div>
      } @else {
        <div class="sv-table-wrap">
          <table class="sv-table">
            <thead>
              <tr>
                <th scope="col">{{ t('ui.field.created-at') }}</th>
                <th scope="col">{{ t('ui.field.bookmark') }}</th>
                <th scope="col">{{ t('ui.field.reason') }}</th>
                <th scope="col">{{ t('ui.field.comment') }}</th>
                <th scope="col">{{ t('ui.field.status') }}</th>
                <th scope="col">
                  <span class="sv-visually-hidden">{{ t('ui.field.actions') }}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              @for (report of page.items; track report.id) {
                <tr [attr.data-ctx]="'report:' + report.id">
                  <td>
                    <time [attr.datetime]="report.createdAt">{{ formatDate(report.createdAt) }}</time>
                  </td>
                  <td><app-bookmark-cell [bookmarkId]="report.bookmarkId" /></td>
                  <td>
                    <span class="sv-badge">{{ t('ui.report.reason.' + report.reason) }}</span>
                  </td>
                  <td>{{ report.comment }}</td>
                  <td>
                    <span class="sv-badge" [class.sv-badge--danger]="report.status === 'actioned'">
                      {{ t('ui.report.status.' + report.status) }}
                    </span>
                    @if (report.resolutionNote) {
                      <div class="sv-field-hint">{{ report.resolutionNote }}</div>
                    }
                  </td>
                  <td class="sv-cell-actions">
                    @if (report.status === 'open') {
                      <button
                        type="button"
                        class="sv-button sv-button--ghost sv-button--sm"
                        (click)="editing.set(report)"
                      >
                        {{ t('ui.action.edit') }}
                      </button>
                      <button
                        type="button"
                        class="sv-button sv-button--ghost sv-button--sm"
                        (click)="withdrawing.set(report)"
                      >
                        {{ t('ui.action.withdraw') }}
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
      <sv-pagination [page]="page.page" [totalPages]="page.totalPages" (paged)="pageIndex.set($event)" />
      @if (editing(); as report) {
        <app-edit-report-dialog
          [report]="report"
          (saved)="reports.reload()"
          (closed)="editing.set(null)"
        />
      }
      @if (withdrawing(); as report) {
        <sv-confirm-dialog
          [title]="t('ui.action.withdraw')"
          [body]="t('ui.confirm.withdraw-report')"
          [ctx]="'report:' + report.id"
          [confirmLabel]="t('ui.action.withdraw')"
          [cancelLabel]="t('ui.action.cancel')"
          [pending]="withdrawPending()"
          (confirmed)="confirmWithdraw(report)"
          (closed)="withdrawing.set(null)"
        />
      }
    }
  `,
})
export class MyReportsPage {
  private readonly i18n = inject(I18n);
  protected readonly t = this.i18n.t;
  private readonly api = inject(BookmarksApi);
  private readonly toast = inject(ToastStore);

  protected readonly statuses = STATUSES;
  protected readonly status = signal<ReportStatus | ''>('');
  protected readonly pageIndex = signal(0);
  protected readonly editing = signal<Report | null>(null);
  protected readonly withdrawing = signal<Report | null>(null);
  protected readonly withdrawPending = signal(false);

  protected readonly reports = new Query(
    computed(() => ({ status: this.status(), page: this.pageIndex() })),
    ({ status, page }): Promise<Page<Report>> => this.api.listMyReports(status, page),
  );

  protected setStatus(status: ReportStatus | ''): void {
    this.status.set(status);
    this.pageIndex.set(0);
  }

  protected formatDate(value: string): string {
    return new Date(value).toLocaleString(this.i18n.resolvedLanguage());
  }

  protected async confirmWithdraw(report: Report): Promise<void> {
    this.withdrawPending.set(true);
    try {
      await this.api.withdrawReport(report.id);
      // withdrawal frees the slot (SPEC rule 13) — the feed's session-local
      // "Reported" marker must not outlive the report
      removeReportedId(report.bookmarkId);
      this.withdrawing.set(null);
      this.toast.push(this.t('ui.toast.report-withdrawn'));
      this.reports.reload();
    } catch (error) {
      this.toast.push(messageOf(error), 'danger');
    } finally {
      this.withdrawPending.set(false);
    }
  }
}
