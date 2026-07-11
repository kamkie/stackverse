import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { messageOf } from '../api/problem';
import type { Bookmark } from '../api/types';
import { SessionStore } from '../auth/session';
import { BookmarksApi, TagsStore, type BookmarkFilters } from '../bookmarks/api';
import { BookmarkCard } from '../bookmarks/bookmark-card';
import { BookmarkFormDialog } from '../bookmarks/bookmark-form-dialog';
import { BookmarkList } from '../bookmarks/bookmark-list';
import { BookmarkListStore } from '../bookmarks/list-store';
import { TagSidebar } from '../bookmarks/tag-sidebar';
import { debounced } from '../core/debounce';
import { ToastStore } from '../core/toast';
import { I18n } from '../i18n/i18n';
import { ConfirmDialog } from '../shared/confirm-dialog';
import { Loading, LoginPrompt } from '../shared/states';

type DialogState = { mode: 'create' } | { mode: 'edit'; bookmark: Bookmark } | null;

@Component({
  selector: 'app-my-bookmarks-page',
  imports: [
    BookmarkCard,
    BookmarkFormDialog,
    BookmarkList,
    ConfirmDialog,
    FormsModule,
    Loading,
    LoginPrompt,
    TagSidebar,
  ],
  template: `
    <!-- This page is owner-only: anonymous visitors get a login prompt instead
         of an Add/search toolbar whose every action would just 401. Gated on
         the session, not /api/v1/me — that call is 403 for blocked users, who
         must still reach the list to see the localized "blocked" error. -->
    @if (session.pending()) {
      <section class="sv-content">
        <h1 class="sv-page-title">{{ t('ui.nav.my-bookmarks') }}</h1>
        <sv-loading />
      </section>
    } @else if (!session.authenticated()) {
      <section class="sv-content">
        <h1 class="sv-page-title">{{ t('ui.nav.my-bookmarks') }}</h1>
        <sv-login-prompt />
      </section>
    } @else {
      <div class="sv-layout">
        <app-tag-sidebar [activeTags]="activeTags()" (toggleTag)="toggleTag($event)" />
        <section class="sv-content">
          <h1 class="sv-page-title">{{ t('ui.nav.my-bookmarks') }}</h1>
          <div class="sv-toolbar">
            <input
              type="search"
              class="sv-input"
              name="search"
              [placeholder]="t('ui.bookmarks.search.placeholder')"
              [attr.aria-label]="t('ui.bookmarks.search.placeholder')"
              [ngModel]="search()"
              (ngModelChange)="search.set($event)"
            />
            <button
              type="button"
              class="sv-button sv-button--primary"
              (click)="dialog.set({ mode: 'create' })"
            >
              {{ t('ui.action.add') }}
            </button>
          </div>
          <app-bookmark-list
            [store]="list"
            [emptyMessage]="filtered() ? t('ui.bookmarks.no-matches') : undefined"
          >
            <ng-template let-bookmark>
              <li
                app-bookmark-card
                [bookmark]="bookmark"
                [activeTags]="activeTags()"
                (toggleTag)="toggleTag($event)"
              >
                <div class="sv-bookmark-actions">
                  <button
                    type="button"
                    class="sv-button sv-button--ghost sv-button--sm"
                    (click)="dialog.set({ mode: 'edit', bookmark: bookmark })"
                  >
                    {{ t('ui.action.edit') }}
                  </button>
                  <button
                    type="button"
                    class="sv-button sv-button--ghost sv-button--sm"
                    (click)="deleting.set(bookmark)"
                  >
                    {{ t('ui.action.delete') }}
                  </button>
                </div>
              </li>
            </ng-template>
          </app-bookmark-list>
          @if (dialog(); as state) {
            <app-bookmark-form-dialog
              [bookmark]="state.mode === 'edit' ? state.bookmark : undefined"
              (saved)="onMutated()"
              (closed)="dialog.set(null)"
            />
          }
          @if (deleting(); as bookmark) {
            <sv-confirm-dialog
              [title]="t('ui.action.delete') + ' — ' + bookmark.title"
              [body]="t('ui.confirm.delete-bookmark')"
              [ctx]="'bookmark:' + bookmark.id"
              [confirmLabel]="t('ui.action.delete')"
              [cancelLabel]="t('ui.action.cancel')"
              [pending]="deletePending()"
              (confirmed)="confirmDelete(bookmark)"
              (closed)="deleting.set(null)"
            />
          }
        </section>
      </div>
    }
  `,
})
export class MyBookmarksPage {
  protected readonly t = inject(I18n).t;
  protected readonly session = inject(SessionStore);
  private readonly api = inject(BookmarksApi);
  private readonly tags = inject(TagsStore);
  private readonly toast = inject(ToastStore);

  protected readonly activeTags = signal<string[]>([]);
  protected readonly search = signal('');
  private readonly q = debounced(this.search, 300);
  private readonly filters = computed<BookmarkFilters>(() => ({
    tags: this.activeTags(),
    q: this.q(),
  }));

  // The list is owner-only: don't fire the query into a guaranteed 401 while
  // anonymous (the page renders a login prompt instead).
  protected readonly list = new BookmarkListStore(
    this.api,
    this.filters,
    computed(() => this.session.authenticated()),
  );

  protected readonly dialog = signal<DialogState>(null);
  protected readonly deleting = signal<Bookmark | null>(null);
  protected readonly deletePending = signal(false);

  protected readonly filtered = computed(() => this.q() !== '' || this.activeTags().length > 0);

  protected toggleTag(tag: string): void {
    this.activeTags.update((tags) =>
      tags.includes(tag) ? tags.filter((existing) => existing !== tag) : [...tags, tag],
    );
  }

  protected onMutated(): void {
    this.list.reload();
    this.tags.reload();
  }

  protected async confirmDelete(bookmark: Bookmark): Promise<void> {
    this.deletePending.set(true);
    try {
      await this.api.deleteBookmark(bookmark.id);
      this.deleting.set(null);
      this.toast.push(this.t('ui.toast.bookmark-deleted'), 'success');
      this.onMutated();
    } catch (error) {
      this.toast.push(messageOf(error), 'danger');
    } finally {
      this.deletePending.set(false);
    }
  }
}
