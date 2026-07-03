import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MeStore } from '../auth/me';
import { SessionStore } from '../auth/session';
import { I18n } from '../i18n/i18n';
import { Loading, LoginPrompt } from '../shared/states';

/**
 * Role-gated admin shell: navigation shows only what the caller's roles from
 * `/api/v1/me` allow — moderators see dashboard + reports, admins everything.
 */
@Component({
  selector: 'app-admin-layout',
  imports: [Loading, LoginPrompt, RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    @if (session.pending() || (session.authenticated() && me.pending())) {
      <sv-loading />
    } @else if (!session.authenticated()) {
      <sv-login-prompt />
    } @else if (!me.isModerator()) {
      <div class="sv-alert sv-alert--danger" role="alert">403</div>
    } @else {
      <div class="sv-layout">
        <aside class="sv-sidebar">
          <h2 class="sv-sidebar-title">{{ t('ui.nav.admin') }}</h2>
          <nav class="sv-nav sv-nav--vertical" [attr.aria-label]="t('ui.nav.admin')">
            <a
              routerLink="/admin"
              routerLinkActive="is-active"
              [routerLinkActiveOptions]="{ exact: true }"
              class="sv-nav-link"
            >
              {{ t('ui.admin.dashboard') }}
            </a>
            <a routerLink="/admin/reports" routerLinkActive="is-active" class="sv-nav-link">
              {{ t('ui.admin.reports') }}
            </a>
            @if (me.isAdmin()) {
              <a routerLink="/admin/users" routerLinkActive="is-active" class="sv-nav-link">
                {{ t('ui.admin.users') }}
              </a>
              <a routerLink="/admin/audit" routerLinkActive="is-active" class="sv-nav-link">
                {{ t('ui.admin.audit') }}
              </a>
              <a routerLink="/admin/messages" routerLinkActive="is-active" class="sv-nav-link">
                {{ t('ui.admin.messages') }}
              </a>
            }
          </nav>
        </aside>
        <section class="sv-content">
          <router-outlet />
        </section>
      </div>
    }
  `,
})
export class AdminLayout {
  protected readonly t = inject(I18n).t;
  protected readonly session = inject(SessionStore);
  protected readonly me = inject(MeStore);
}
