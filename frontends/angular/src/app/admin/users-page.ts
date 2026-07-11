import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { fieldErrorFor, isConflict, messageOf } from '../api/problem';
import type { FieldError, Page, UserAccount } from '../api/types';
import { MeStore } from '../auth/me';
import { debounced } from '../core/debounce';
import { Query } from '../core/query';
import { ToastStore } from '../core/toast';
import { I18n } from '../i18n/i18n';
import { Dialog } from '../shared/dialog';
import { Field } from '../shared/field';
import { Pagination } from '../shared/pagination';
import { ErrorState, Loading } from '../shared/states';
import { AdminApi } from './api';

@Component({
  selector: 'app-block-dialog',
  imports: [Dialog, Field, FormsModule],
  template: `
    <sv-dialog
      [title]="t('ui.action.block') + ' — ' + user().username"
      [ctx]="'user:' + user().username"
      (closed)="closed.emit()"
    >
      <form class="sv-form" (ngSubmit)="submit()">
        <sv-field
          [label]="t('ui.field.reason')"
          forId="block-reason"
          [error]="fieldError('reason')"
        >
          <textarea
            id="block-reason"
            class="sv-textarea"
            name="reason"
            [(ngModel)]="reason"
            [attr.aria-invalid]="fieldError('reason') ? true : null"
          ></textarea>
        </sv-field>
        @if (conflict()) {
          <div class="sv-alert sv-alert--warning" role="alert">{{ errorMessage() }}</div>
        }
        <div class="sv-form-actions">
          <button type="button" class="sv-button" (click)="closed.emit()">
            {{ t('ui.action.cancel') }}
          </button>
          <button type="submit" class="sv-button sv-button--danger" [disabled]="pending()">
            {{ t('ui.action.block') }}
          </button>
        </div>
      </form>
    </sv-dialog>
  `,
})
export class BlockDialog {
  protected readonly t = inject(I18n).t;
  private readonly api = inject(AdminApi);

  readonly user = input.required<UserAccount>();
  readonly closed = output<void>();
  readonly blocked = output<void>();

  protected reason = '';
  protected readonly pending = signal(false);
  protected readonly error = signal<unknown>(null);

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
      await this.api.setUserStatus(this.user().username, {
        status: 'blocked',
        reason: this.reason,
      });
      this.blocked.emit();
      this.closed.emit();
    } catch (error) {
      this.error.set(error);
    } finally {
      this.pending.set(false);
    }
  }
}

/** Searchable user directory with block/unblock (admin). */
@Component({
  selector: 'app-users-page',
  imports: [BlockDialog, ErrorState, FormsModule, Loading, Pagination],
  template: `
    @if (users.error() !== null) {
      <sv-error-state [error]="users.error()" />
    } @else {
      <h1 class="sv-page-title">{{ t('ui.admin.users') }}</h1>
      <div class="sv-toolbar">
        <input
          type="search"
          class="sv-input"
          name="search"
          [placeholder]="t('ui.users.search.placeholder')"
          [attr.aria-label]="t('ui.users.search.placeholder')"
          [ngModel]="search()"
          (ngModelChange)="setSearch($event)"
        />
      </div>
      @if (users.pending()) {
        <sv-loading />
      } @else if (users.data(); as page) {
        <div class="sv-table-wrap">
          <table class="sv-table">
            <thead>
              <tr>
                <th scope="col">{{ t('ui.field.username') }}</th>
                <th scope="col">{{ t('ui.field.last-seen') }}</th>
                <th scope="col">{{ t('ui.field.bookmarks') }}</th>
                <th scope="col">{{ t('ui.field.status') }}</th>
                <th scope="col">
                  <span class="sv-visually-hidden">{{ t('ui.field.actions') }}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              @for (user of page.items; track user.username) {
                <tr [attr.data-ctx]="'user:' + user.username">
                  <td>{{ user.username }}</td>
                  <td>
                    <time [attr.datetime]="user.lastSeen">{{ formatDate(user.lastSeen) }}</time>
                  </td>
                  <td>{{ user.bookmarkCount }}</td>
                  <td>
                    @if (user.status === 'blocked') {
                      <span class="sv-badge sv-badge--danger" [title]="user.blockedReason">
                        {{ t('ui.user.status.blocked') }}
                      </span>
                    } @else {
                      <span class="sv-badge sv-badge--success">
                        {{ t('ui.user.status.active') }}
                      </span>
                    }
                  </td>
                  <td class="sv-cell-actions">
                    @if (user.status === 'blocked') {
                      <button
                        type="button"
                        class="sv-button sv-button--sm"
                        [disabled]="mutating()"
                        (click)="unblock(user)"
                      >
                        {{ t('ui.action.unblock') }}
                      </button>
                    } @else if (me.user() !== undefined && me.user()!.username !== user.username) {
                      <!-- The API rejects self-blocking, so don't offer it.
                           Wait for /me before showing any Block button — while
                           it is pending every row would pass the !== check,
                           including the admin's own. -->
                      <button
                        type="button"
                        class="sv-button sv-button--sm"
                        (click)="blocking.set(user)"
                      >
                        {{ t('ui.action.block') }}
                      </button>
                    }
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
      @if (blocking(); as user) {
        <app-block-dialog [user]="user" (blocked)="users.reload()" (closed)="blocking.set(null)" />
      }
    }
  `,
})
export class UsersPage {
  private readonly i18n = inject(I18n);
  protected readonly t = this.i18n.t;
  private readonly api = inject(AdminApi);
  protected readonly me = inject(MeStore);
  private readonly toast = inject(ToastStore);

  protected readonly search = signal('');
  private readonly q = debounced(this.search, 300);
  protected readonly pageIndex = signal(0);
  protected readonly blocking = signal<UserAccount | null>(null);
  protected readonly mutating = signal(false);

  protected readonly users = new Query(
    computed(() => ({ q: this.q(), page: this.pageIndex() })),
    ({ q, page }: { q: string; page: number }): Promise<Page<UserAccount>> =>
      this.api.listUsers(q, page),
  );

  protected setSearch(value: string): void {
    this.search.set(value);
    this.pageIndex.set(0);
  }

  protected formatDate(value: string): string {
    return new Date(value).toLocaleString(this.i18n.resolvedLanguage());
  }

  protected async unblock(user: UserAccount): Promise<void> {
    this.mutating.set(true);
    try {
      await this.api.setUserStatus(user.username, { status: 'active' });
      this.users.reload();
    } catch (error) {
      this.toast.push(messageOf(error), 'danger');
    } finally {
      this.mutating.set(false);
    }
  }
}
