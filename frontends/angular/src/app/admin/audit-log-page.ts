import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { debounced } from '../core/debounce';
import { Query } from '../core/query';
import { I18n } from '../i18n/i18n';
import { Pagination } from '../shared/pagination';
import { ErrorState, Loading } from '../shared/states';
import { AdminApi, type AuditQuery } from './api';

/**
 * Actions emitted by the reference backend, offered as <datalist> suggestions.
 * The contract keeps `action` an open string, so the field stays free-text.
 */
const KNOWN_ACTIONS = [
  'message.created',
  'message.updated',
  'message.deleted',
  'report.resolved',
  'bookmark.status-changed',
  'user.blocked',
  'user.unblocked',
];

/**
 * The last instant of a local calendar day as an ISO instant. Backends store
 * microsecond timestamps and compare inclusively, so the millisecond Date
 * resolves to is extended to microseconds — otherwise entries in the day's
 * final millisecond would be filtered out.
 */
function endOfDayIso(day: string): string {
  return new Date(`${day}T23:59:59.999`).toISOString().replace('.999Z', '.999999Z');
}

/** Filterable, paginated browser over the append-only audit trail (admin). */
@Component({
  selector: 'app-audit-log-page',
  imports: [ErrorState, FormsModule, Loading, Pagination],
  template: `
    @if (audit.error() !== null) {
      <sv-error-state [error]="audit.error()" />
    } @else {
      <h1 class="sv-page-title">{{ t('ui.admin.audit') }}</h1>
      <div class="sv-toolbar">
        <input
          class="sv-input"
          name="actor"
          [placeholder]="t('ui.field.actor')"
          [ngModel]="actorInput()"
          (ngModelChange)="setActor($event)"
        />
        <input
          class="sv-input"
          name="action"
          [placeholder]="t('ui.audit.action.placeholder')"
          [attr.aria-label]="t('ui.audit.action.placeholder')"
          list="audit-log-known-actions"
          [ngModel]="actionInput()"
          (ngModelChange)="setAction($event)"
        />
        <datalist id="audit-log-known-actions">
          @for (action of knownActions; track action) {
            <option [value]="action"></option>
          }
        </datalist>
        <label class="sv-toolbar-field">
          <span class="sv-label">{{ t('ui.field.from') }}</span>
          <input
            type="date"
            class="sv-input"
            name="from"
            [ngModel]="from()"
            (ngModelChange)="setFrom($event)"
          />
        </label>
        <label class="sv-toolbar-field">
          <span class="sv-label">{{ t('ui.field.to') }}</span>
          <input
            type="date"
            class="sv-input"
            name="to"
            [ngModel]="to()"
            (ngModelChange)="setTo($event)"
          />
        </label>
        <button type="button" class="sv-button sv-button--ghost" (click)="clearFilters()">
          {{ t('ui.action.clear-filters') }}
        </button>
      </div>
      @if (audit.pending()) {
        <sv-loading />
      } @else if (audit.data(); as page) {
        <div class="sv-table-wrap">
          <table class="sv-table">
            <thead>
              <tr>
                <th scope="col">{{ t('ui.field.created-at') }}</th>
                <th scope="col">{{ t('ui.field.actor') }}</th>
                <th scope="col">{{ t('ui.field.action') }}</th>
                <th scope="col">{{ t('ui.field.target') }}</th>
              </tr>
            </thead>
            <tbody>
              @for (entry of page.items; track entry.id) {
                <tr>
                  <td>
                    <time [attr.datetime]="entry.createdAt">{{ formatDate(entry.createdAt) }}</time>
                  </td>
                  <td>{{ entry.actor }}</td>
                  <td>
                    <span class="sv-badge">{{ entry.action }}</span>
                  </td>
                  <td class="sv-cell-mono">
                    {{ entry.targetType }}/{{ entry.targetId.slice(0, 8) }}
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <sv-pagination
          [page]="page.page"
          [totalPages]="page.totalPages"
          (paged)="pageIndex.set($event)"
        />
      }
    }
  `,
})
export class AuditLogPage {
  private readonly i18n = inject(I18n);
  protected readonly t = this.i18n.t;
  private readonly api = inject(AdminApi);

  protected readonly knownActions = KNOWN_ACTIONS;
  protected readonly actorInput = signal('');
  protected readonly actionInput = signal('');
  private readonly actor = debounced(this.actorInput, 300);
  private readonly action = debounced(this.actionInput, 300);
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly pageIndex = signal(0);

  protected readonly audit = new Query(
    computed<AuditQuery>(() => ({
      ...(this.actor() ? { actor: this.actor() } : {}),
      ...(this.action() ? { action: this.action() } : {}),
      // The date inputs select whole local calendar days; the API takes instants
      // and the backend compares both bounds inclusively, so "from" becomes the
      // first instant of the selected day and "to" the last.
      ...(this.from() ? { from: new Date(`${this.from()}T00:00:00`).toISOString() } : {}),
      ...(this.to() ? { to: endOfDayIso(this.to()) } : {}),
      page: this.pageIndex(),
    })),
    (query: AuditQuery) => this.api.listAuditEntries(query),
  );

  protected setActor(value: string): void {
    this.actorInput.set(value);
    this.pageIndex.set(0);
  }

  protected setAction(value: string): void {
    this.actionInput.set(value);
    this.pageIndex.set(0);
  }

  protected setFrom(value: string): void {
    this.from.set(value);
    this.pageIndex.set(0);
  }

  protected setTo(value: string): void {
    this.to.set(value);
    this.pageIndex.set(0);
  }

  protected clearFilters(): void {
    this.actorInput.set('');
    this.actionInput.set('');
    this.from.set('');
    this.to.set('');
    this.pageIndex.set(0);
  }

  protected formatDate(value: string): string {
    return new Date(value).toLocaleString(this.i18n.resolvedLanguage());
  }
}
