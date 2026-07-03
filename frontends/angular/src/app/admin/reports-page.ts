import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { messageOf } from '../api/problem';
import type { Page, Report, ReportStatus } from '../api/types';
import { BookmarkCell } from '../bookmarks/bookmark-cell';
import { Query } from '../core/query';
import { ToastStore } from '../core/toast';
import { I18n } from '../i18n/i18n';
import { Pagination } from '../shared/pagination';
import { ErrorState, Loading } from '../shared/states';
import { AdminApi } from './api';

const STATUSES: ReportStatus[] = ['open', 'dismissed', 'actioned'];

/** Moderation queue: dismiss leaves the bookmark alone, action hides it. */
@Component({
  selector: 'app-admin-reports-page',
  imports: [BookmarkCell, ErrorState, FormsModule, Loading, Pagination],
  template: `
    @if (reports.pending()) {
      <sv-loading />
    } @else if (reports.error() !== null) {
      <sv-error-state [error]="reports.error()" />
    } @else if (reports.data(); as page) {
      <h1 class="sv-page-title">{{ t('ui.admin.reports') }}</h1>
      <div class="sv-toolbar">
        <select
          class="sv-select"
          name="status"
          [ngModel]="status()"
          (ngModelChange)="setStatus($event)"
        >
          @for (option of statuses; track option) {
            <option [value]="option">{{ t('ui.report.status.' + option) }}</option>
          }
        </select>
      </div>
      @if (page.items.length === 0) {
        <div class="sv-empty">{{ t('ui.reports.empty') }}</div>
      } @else {
        <div class="sv-table-wrap">
          <table class="sv-table">
            <thead>
              <tr>
                <th scope="col">{{ t('ui.field.created-at') }}</th>
                <th scope="col">{{ t('ui.field.bookmark') }}</th>
                <th scope="col">{{ t('ui.field.reporter') }}</th>
                <th scope="col">{{ t('ui.field.reason') }}</th>
                <th scope="col">{{ t('ui.field.comment') }}</th>
                <th scope="col">
                  <span class="sv-visually-hidden">{{ t('ui.field.actions') }}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              @for (report of page.items; track report.id) {
                <tr [attr.data-ctx]="'report:' + report.id">
                  <td>
                    <time [attr.datetime]="report.createdAt">
                      {{ formatDate(report.createdAt) }}
                    </time>
                  </td>
                  <td><app-bookmark-cell [bookmarkId]="report.bookmarkId" /></td>
                  <td>{{ report.reporter }}</td>
                  <td>
                    <span class="sv-badge">{{ t('ui.report.reason.' + report.reason) }}</span>
                  </td>
                  <td>{{ report.comment }}</td>
                  <td class="sv-cell-actions">
                    @if (report.status === 'open') {
                      <button
                        type="button"
                        class="sv-button sv-button--sm"
                        [disabled]="resolving()"
                        (click)="resolve(report, 'dismissed')"
                      >
                        {{ t('ui.action.dismiss') }}
                      </button>
                      <button
                        type="button"
                        class="sv-button sv-button--danger sv-button--sm"
                        [disabled]="resolving()"
                        (click)="resolve(report, 'actioned')"
                      >
                        {{ t('ui.action.action') }}
                      </button>
                    } @else {
                      <!-- decisions are revisable (SPEC rule 14): flip the
                           disposition or send the report back to the open queue -->
                      <span
                        class="sv-badge"
                        [class.sv-badge--danger]="report.status === 'actioned'"
                      >
                        {{ t('ui.report.status.' + report.status) }}
                      </span>
                      @if (report.status === 'actioned') {
                        <button
                          type="button"
                          class="sv-button sv-button--ghost sv-button--sm"
                          [disabled]="resolving()"
                          (click)="resolve(report, 'dismissed')"
                        >
                          {{ t('ui.action.dismiss') }}
                        </button>
                      } @else {
                        <button
                          type="button"
                          class="sv-button sv-button--ghost sv-button--sm"
                          [disabled]="resolving()"
                          (click)="resolve(report, 'actioned')"
                        >
                          {{ t('ui.action.action') }}
                        </button>
                      }
                      <button
                        type="button"
                        class="sv-button sv-button--ghost sv-button--sm"
                        [disabled]="resolving()"
                        (click)="resolve(report, 'open')"
                      >
                        {{ t('ui.action.reopen') }}
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
      <sv-pagination
        [page]="page.page"
        [totalPages]="page.totalPages"
        (paged)="pageIndex.set($event)"
      />
    }
  `,
})
export class ReportsPage {
  private readonly i18n = inject(I18n);
  protected readonly t = this.i18n.t;
  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastStore);

  protected readonly statuses = STATUSES;
  protected readonly status = signal<ReportStatus>('open');
  protected readonly pageIndex = signal(0);
  protected readonly resolving = signal(false);

  protected readonly reports = new Query(
    computed(() => ({ status: this.status(), page: this.pageIndex() })),
    ({ status, page }: { status: ReportStatus; page: number }): Promise<Page<Report>> =>
      this.api.listReports(status, page),
  );

  protected setStatus(status: ReportStatus): void {
    this.status.set(status);
    this.pageIndex.set(0);
  }

  protected formatDate(value: string): string {
    return new Date(value).toLocaleString(this.i18n.resolvedLanguage());
  }

  protected async resolve(report: Report, resolution: 'open' | 'dismissed' | 'actioned') {
    this.resolving.set(true);
    try {
      await this.api.resolveReport(report.id, { resolution });
      this.reports.reload();
    } catch (error) {
      this.toast.push(messageOf(error), 'danger');
    } finally {
      this.resolving.set(false);
    }
  }
}
