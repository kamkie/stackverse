import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MeStore } from './auth/me';
import { LOGIN_URL, SessionStore } from './auth/session';
import { ToastStore } from './core/toast';
import { I18n } from './i18n/i18n';
import { SUPPORTED_LANGUAGES } from './i18n/languages';

const THEME_STORAGE_KEY = 'stackverse.theme';
const THEME_OPTIONS = ['auto', 'light', 'dark'] as const;
type ThemeOption = (typeof THEME_OPTIONS)[number];

function readStoredTheme(): ThemeOption {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return stored === 'light' || stored === 'dark' ? stored : 'auto';
  } catch {
    return 'auto';
  }
}

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    @if (i18n.ready()) {
      <div class="sv-app">
        <header class="sv-header">
          <a routerLink="/" class="sv-brand">{{ t('ui.app.title') }}</a>
          <nav class="sv-nav">
            <!-- Gated on the session, not /api/v1/me: that call is disabled
                 while anonymous and returns 403 for blocked-but-authenticated
                 users, who must still see their navigation. Hidden while the
                 session is loading so anonymous visitors never see the link
                 flash. -->
            @if (session.authenticated()) {
              <a routerLink="/bookmarks" routerLinkActive="is-active" class="sv-nav-link">
                {{ t('ui.nav.my-bookmarks') }}
              </a>
              <a routerLink="/reports" routerLinkActive="is-active" class="sv-nav-link">
                {{ t('ui.nav.my-reports') }}
              </a>
            }
            <a routerLink="/feed" routerLinkActive="is-active" class="sv-nav-link">
              {{ t('ui.nav.public-feed') }}
            </a>
            @if (me.isModerator()) {
              <a routerLink="/admin" routerLinkActive="is-active" class="sv-nav-link">
                {{ t('ui.nav.admin') }}
              </a>
            }
          </nav>
          <div class="sv-header-actions">
            <!-- The persisted choice is applied before first paint by the
                 inline script in index.html; tokens.css maps data-theme (or
                 its absence = auto) to colors. -->
            <div class="sv-theme-switch" role="group" [attr.aria-label]="t('ui.theme.label')">
              @for (option of themeOptions; track option) {
                <button
                  type="button"
                  class="sv-theme-option"
                  [class.is-active]="theme() === option"
                  (click)="applyTheme(option)"
                >
                  {{ t('ui.theme.' + option) }}
                </button>
              }
            </div>
            <div class="sv-lang-switch" role="group" aria-label="language">
              @for (code of languages; track code) {
                <button
                  type="button"
                  class="sv-lang-option"
                  [class.is-active]="currentLanguage() === code"
                  [attr.lang]="code"
                  (click)="i18n.setLang(code)"
                >
                  {{ code.toUpperCase() }}
                </button>
              }
            </div>
            @if (!session.pending()) {
              @if (session.authenticated()) {
                <span class="sv-username">{{ session.username() }}</span>
                <button
                  type="button"
                  class="sv-button sv-button--ghost sv-button--sm"
                  [disabled]="logoutPending()"
                  (click)="logout()"
                >
                  {{ t('ui.action.logout') }}
                </button>
              } @else {
                <a class="sv-button sv-button--primary sv-button--sm" [href]="loginUrl">
                  {{ t('ui.action.login') }}
                </a>
              }
            }
          </div>
        </header>
        <main class="sv-main">
          <router-outlet />
        </main>
      </div>
    } @else {
      <!-- Hold rendering until the first bundle arrives so screens never flash keys. -->
      <div class="sv-loading">
        <span class="sv-spinner"></span>
      </div>
    }
    <div class="sv-toast-region" role="status" aria-live="polite">
      @for (toast of toasts.items(); track toast.id) {
        <div [class]="'sv-toast sv-toast--' + toast.variant">{{ toast.message }}</div>
      }
    </div>
  `,
})
export class App {
  protected readonly i18n = inject(I18n);
  protected readonly t = this.i18n.t;
  protected readonly session = inject(SessionStore);
  protected readonly me = inject(MeStore);
  protected readonly toasts = inject(ToastStore);
  private readonly router = inject(Router);

  protected readonly loginUrl = LOGIN_URL;
  protected readonly themeOptions = THEME_OPTIONS;
  protected readonly languages = SUPPORTED_LANGUAGES;

  protected readonly theme = signal<ThemeOption>(readStoredTheme());
  protected readonly logoutPending = signal(false);

  protected currentLanguage(): string {
    return this.i18n.lang() ?? this.i18n.resolvedLanguage();
  }

  protected applyTheme(next: ThemeOption): void {
    this.theme.set(next);
    const root = document.documentElement;
    if (next === 'auto') root.removeAttribute('data-theme');
    else root.setAttribute('data-theme', next);
    try {
      if (next === 'auto') localStorage.removeItem(THEME_STORAGE_KEY);
      else localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch {
      // storage unavailable — the choice just won't survive a reload
    }
  }

  protected async logout(): Promise<void> {
    this.logoutPending.set(true);
    try {
      await this.session.logout();
    } finally {
      this.logoutPending.set(false);
      // Land on the public feed — the only page an anonymous visitor can use.
      await this.router.navigate(['/feed']);
    }
  }
}
