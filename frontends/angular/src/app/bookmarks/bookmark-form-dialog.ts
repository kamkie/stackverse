import { Component, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { fieldErrorFor, isConflict } from '../api/problem';
import type { Bookmark, BookmarkInput, FieldError, Visibility } from '../api/types';
import { I18n } from '../i18n/i18n';
import { Dialog } from '../shared/dialog';
import { Field } from '../shared/field';
import { BookmarksApi } from './api';

/**
 * Create/edit form. Validation failures come back as RFC 9457 problem
 * documents whose `errors` array is rendered on the matching fields —
 * never as a generic toast.
 */
@Component({
  selector: 'app-bookmark-form-dialog',
  imports: [Dialog, Field, FormsModule],
  template: `
    <sv-dialog
      [title]="t(bookmark() ? 'ui.bookmarks.dialog.edit' : 'ui.bookmarks.dialog.add')"
      [ctx]="bookmark() ? 'bookmark:' + bookmark()!.id : undefined"
      (closed)="closed.emit()"
    >
      <form class="sv-form" (ngSubmit)="submit()">
        <sv-field [label]="t('ui.field.url')" forId="bookmark-url" [error]="fieldError('url')">
          <input
            id="bookmark-url"
            class="sv-input"
            name="url"
            [(ngModel)]="url"
            [attr.aria-invalid]="fieldError('url') ? true : null"
            autofocus
          />
        </sv-field>
        <sv-field
          [label]="t('ui.field.title')"
          forId="bookmark-title"
          [error]="fieldError('title')"
        >
          <input
            id="bookmark-title"
            class="sv-input"
            name="title"
            [(ngModel)]="title"
            [attr.aria-invalid]="fieldError('title') ? true : null"
          />
        </sv-field>
        <sv-field
          [label]="t('ui.field.notes')"
          forId="bookmark-notes"
          [error]="fieldError('notes')"
        >
          <textarea
            id="bookmark-notes"
            class="sv-textarea"
            name="notes"
            [(ngModel)]="notes"
            [attr.aria-invalid]="fieldError('notes') ? true : null"
          ></textarea>
        </sv-field>
        <sv-field
          [label]="t('ui.field.tags')"
          forId="bookmark-tags"
          [hint]="t('ui.field.tags.hint')"
          [error]="fieldError('tags')"
        >
          <input
            id="bookmark-tags"
            class="sv-input"
            name="tags"
            [(ngModel)]="tags"
            [attr.aria-invalid]="fieldError('tags') ? true : null"
          />
        </sv-field>
        <sv-field
          [label]="t('ui.field.visibility')"
          forId="bookmark-visibility"
          [error]="fieldError('visibility')"
        >
          <select
            id="bookmark-visibility"
            class="sv-select"
            name="visibility"
            [(ngModel)]="visibility"
          >
            <option value="private">{{ t('ui.visibility.private') }}</option>
            <option value="public">{{ t('ui.visibility.public') }}</option>
          </select>
        </sv-field>
        @if (conflict()) {
          <div class="sv-alert sv-alert--warning" role="alert">
            {{ t('error.bookmark.hidden-publish') }}
          </div>
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
export class BookmarkFormDialog implements OnInit {
  protected readonly t = inject(I18n).t;
  private readonly api = inject(BookmarksApi);

  /** Absent for create, present for edit. */
  readonly bookmark = input<Bookmark>();
  readonly closed = output<void>();
  /** Fired after a successful save so the caller can reload list and tags. */
  readonly saved = output<void>();

  protected url = '';
  protected title = '';
  protected notes = '';
  protected tags = '';
  protected visibility: Visibility = 'private';

  protected readonly pending = signal(false);
  protected readonly error = signal<unknown>(null);

  ngOnInit(): void {
    const bookmark = this.bookmark();
    if (!bookmark) return;
    this.url = bookmark.url;
    this.title = bookmark.title;
    this.notes = bookmark.notes ?? '';
    this.tags = bookmark.tags.join(' ');
    this.visibility = bookmark.visibility;
  }

  protected fieldError(field: string): FieldError | undefined {
    return fieldErrorFor(this.error(), field);
  }

  protected conflict(): boolean {
    return isConflict(this.error());
  }

  protected async submit(): Promise<void> {
    const body: BookmarkInput = {
      url: this.url,
      title: this.title,
      ...(this.notes ? { notes: this.notes } : {}),
      tags: this.tags.split(/[\s,]+/).filter(Boolean),
      visibility: this.visibility,
    };
    this.pending.set(true);
    this.error.set(null);
    try {
      const existing = this.bookmark();
      if (existing) await this.api.updateBookmark(existing.id, body);
      else await this.api.createBookmark(body);
      this.saved.emit();
      this.closed.emit();
    } catch (error) {
      this.error.set(error);
    } finally {
      this.pending.set(false);
    }
  }
}
