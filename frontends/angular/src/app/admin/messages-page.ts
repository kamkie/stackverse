import { Component, computed, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { fieldErrorFor, isConflict, messageOf } from '../api/problem';
import type { FieldError, Message, MessageInput } from '../api/types';
import { debounced } from '../core/debounce';
import { Query } from '../core/query';
import { ToastStore } from '../core/toast';
import { I18n } from '../i18n/i18n';
import { SUPPORTED_LANGUAGES } from '../i18n/languages';
import { ConfirmDialog } from '../shared/confirm-dialog';
import { Dialog } from '../shared/dialog';
import { Field } from '../shared/field';
import { Pagination } from '../shared/pagination';
import { ErrorState, Loading } from '../shared/states';
import { AdminApi, type MessagesQuery } from './api';

@Component({
  selector: 'app-message-form-dialog',
  imports: [Dialog, Field, FormsModule],
  template: `
    <sv-dialog
      [title]="t(message() ? 'ui.messages.dialog.edit' : 'ui.messages.dialog.add')"
      [ctx]="message() ? 'message:' + message()!.id : undefined"
      (closed)="closed.emit()"
    >
      <form class="sv-form" (ngSubmit)="submit()">
        <sv-field [label]="t('ui.field.key')" forId="message-key" [error]="fieldError('key')">
          <input
            id="message-key"
            class="sv-input"
            name="key"
            [(ngModel)]="key"
            [attr.aria-invalid]="fieldError('key') ? true : null"
          />
        </sv-field>
        <sv-field
          [label]="t('ui.field.language')"
          forId="message-language"
          [error]="fieldError('language')"
        >
          <select id="message-language" class="sv-select" name="language" [(ngModel)]="language">
            <!-- The contract allows any ISO 639-1 code; keep a message's own
                 language selectable when it is outside the supported set. -->
            @if (!supported.includes(language)) {
              <option [value]="language">{{ language }}</option>
            }
            @for (code of supported; track code) {
              <option [value]="code">{{ code }}</option>
            }
          </select>
        </sv-field>
        <sv-field [label]="t('ui.field.text')" forId="message-text" [error]="fieldError('text')">
          <textarea
            id="message-text"
            class="sv-textarea"
            name="text"
            [(ngModel)]="text"
            [attr.aria-invalid]="fieldError('text') ? true : null"
          ></textarea>
        </sv-field>
        <sv-field
          [label]="t('ui.field.description')"
          forId="message-description"
          [error]="fieldError('description')"
        >
          <textarea
            id="message-description"
            class="sv-textarea"
            name="description"
            [(ngModel)]="description"
            [attr.aria-invalid]="fieldError('description') ? true : null"
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
export class MessageFormDialog implements OnInit {
  protected readonly t = inject(I18n).t;
  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastStore);

  readonly message = input<Message>();
  readonly closed = output<void>();
  readonly saved = output<void>();

  protected readonly supported: readonly string[] = SUPPORTED_LANGUAGES;
  protected key = '';
  protected language = 'en';
  protected text = '';
  protected description = '';

  protected readonly pending = signal(false);
  protected readonly error = signal<unknown>(null);

  ngOnInit(): void {
    const message = this.message();
    if (!message) return;
    this.key = message.key;
    this.language = message.language;
    this.text = message.text;
    this.description = message.description ?? '';
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
    const body: MessageInput = {
      key: this.key,
      language: this.language,
      text: this.text,
      ...(this.description ? { description: this.description } : {}),
    };
    this.pending.set(true);
    this.error.set(null);
    try {
      const existing = this.message();
      if (existing) await this.api.updateMessage(existing.id, body);
      else await this.api.createMessage(body);
      this.toast.push(this.t(existing ? 'ui.toast.message-updated' : 'ui.toast.message-created'));
      this.saved.emit();
      this.closed.emit();
    } catch (error) {
      this.error.set(error);
    } finally {
      this.pending.set(false);
    }
  }
}

type DialogState = { mode: 'create' } | { mode: 'edit'; message: Message } | null;

/** Runtime-managed localized messages: list, create, edit, delete (admin). */
@Component({
  selector: 'app-messages-page',
  imports: [ConfirmDialog, ErrorState, FormsModule, Loading, MessageFormDialog, Pagination],
  template: `
    @if (messages.error() !== null) {
      <sv-error-state [error]="messages.error()" />
    } @else {
      <h1 class="sv-page-title">{{ t('ui.admin.messages') }}</h1>
      <div class="sv-toolbar">
        <input
          class="sv-input"
          name="q"
          [placeholder]="t('ui.messages.search.placeholder')"
          [attr.aria-label]="t('ui.messages.search.placeholder')"
          [ngModel]="qInput()"
          (ngModelChange)="setQ($event)"
        />
        <select
          class="sv-select"
          name="language"
          [attr.aria-label]="t('ui.field.language')"
          [ngModel]="language()"
          (ngModelChange)="setLanguage($event)"
        >
          <option value="">{{ t('ui.messages.filter.all-languages') }}</option>
          @for (code of supported; track code) {
            <option [value]="code">{{ code }}</option>
          }
        </select>
        <button type="button" class="sv-button sv-button--ghost" (click)="clearFilters()">
          {{ t('ui.action.clear-filters') }}
        </button>
        <button
          type="button"
          class="sv-button sv-button--primary"
          (click)="dialog.set({ mode: 'create' })"
        >
          {{ t('ui.action.add') }}
        </button>
      </div>
      @if (messages.pending()) {
        <sv-loading />
      } @else if (messages.data(); as page) {
        @if (page.items.length === 0) {
          <div class="sv-empty">{{ t('ui.messages.empty') }}</div>
        } @else {
          <div class="sv-table-wrap">
            <table class="sv-table">
              <thead>
                <tr>
                  <th scope="col">{{ t('ui.field.key') }}</th>
                  <th scope="col">{{ t('ui.field.language') }}</th>
                  <th scope="col">{{ t('ui.field.text') }}</th>
                  <th scope="col">
                    <span class="sv-visually-hidden">{{ t('ui.field.actions') }}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                @for (message of page.items; track message.id) {
                  <tr [attr.data-ctx]="'message:' + message.id">
                    <td class="sv-cell-mono">{{ message.key }}</td>
                    <td>
                      <span class="sv-badge">{{ message.language }}</span>
                    </td>
                    <td>{{ message.text }}</td>
                    <td class="sv-cell-actions">
                      <button
                        type="button"
                        class="sv-button sv-button--ghost sv-button--sm"
                        (click)="dialog.set({ mode: 'edit', message: message })"
                      >
                        {{ t('ui.action.edit') }}
                      </button>
                      <button
                        type="button"
                        class="sv-button sv-button--ghost sv-button--sm"
                        (click)="deleting.set(message)"
                      >
                        {{ t('ui.action.delete') }}
                      </button>
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
      @if (dialog(); as state) {
        <app-message-form-dialog
          [message]="state.mode === 'edit' ? state.message : undefined"
          (saved)="onMutated()"
          (closed)="dialog.set(null)"
        />
      }
      @if (deleting(); as message) {
        <sv-confirm-dialog
          [title]="t('ui.action.delete') + ' — ' + message.key"
          [body]="t('ui.confirm.delete-message')"
          [ctx]="'message:' + message.id"
          [confirmLabel]="t('ui.action.delete')"
          [cancelLabel]="t('ui.action.cancel')"
          [pending]="deletePending()"
          (confirmed)="confirmDelete(message)"
          (closed)="deleting.set(null)"
        />
      }
    }
  `,
})
export class MessagesPage {
  private readonly i18n = inject(I18n);
  protected readonly t = this.i18n.t;
  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastStore);

  protected readonly supported: readonly string[] = SUPPORTED_LANGUAGES;
  protected readonly qInput = signal('');
  private readonly q = debounced(this.qInput, 300);
  protected readonly language = signal('');
  protected readonly pageIndex = signal(0);
  protected readonly dialog = signal<DialogState>(null);
  protected readonly deleting = signal<Message | null>(null);
  protected readonly deletePending = signal(false);

  protected readonly messages = new Query(
    computed<MessagesQuery>(() => ({
      ...(this.q() ? { q: this.q() } : {}),
      ...(this.language() ? { language: this.language() } : {}),
      page: this.pageIndex(),
    })),
    (query: MessagesQuery) => this.api.listMessages(query),
  );

  protected setQ(value: string): void {
    this.qInput.set(value);
    this.pageIndex.set(0);
  }

  protected setLanguage(value: string): void {
    this.language.set(value);
    this.pageIndex.set(0);
  }

  protected clearFilters(): void {
    this.qInput.set('');
    this.language.set('');
    this.pageIndex.set(0);
  }

  /**
   * Message writes change the served bundle; revalidate it so visible UI text
   * updates without a language switch (unchanged bundles cost a 304).
   */
  protected onMutated(): void {
    this.messages.reload();
    this.i18n.refresh();
  }

  protected async confirmDelete(message: Message): Promise<void> {
    this.deletePending.set(true);
    try {
      await this.api.deleteMessage(message.id);
      this.deleting.set(null);
      this.toast.push(this.t('ui.toast.message-deleted'));
      this.onMutated();
    } catch (error) {
      this.toast.push(messageOf(error), 'danger');
    } finally {
      this.deletePending.set(false);
    }
  }
}
