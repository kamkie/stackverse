import { Component, inject, input, OnInit } from '@angular/core';
import { isUnauthorized, messageOf } from '../api/problem';
import { LOGIN_URL, SessionStore } from '../auth/session';
import { I18n } from '../i18n/i18n';

@Component({
  selector: 'sv-loading',
  template: `
    <div class="sv-loading" role="status">
      <span class="sv-spinner"></span>
    </div>
  `,
})
export class Loading {}

/** A 401 means the session died — treat as logged out and offer login. */
@Component({
  selector: 'sv-login-prompt',
  template: `
    <div class="sv-empty">
      <a class="sv-button sv-button--primary" [href]="loginUrl">{{ t('ui.action.login') }}</a>
    </div>
  `,
})
export class LoginPrompt implements OnInit {
  protected readonly t = inject(I18n).t;
  protected readonly loginUrl = LOGIN_URL;
  private readonly session = inject(SessionStore);

  ngOnInit(): void {
    // Re-check the session so the header and role-gated navigation flip to
    // logged-out instead of showing a stale username (MeStore follows along).
    void this.session.refresh();
  }
}

@Component({
  selector: 'sv-error-state',
  imports: [LoginPrompt],
  template: `
    @if (unauthorized()) {
      <sv-login-prompt />
    } @else {
      <div class="sv-alert sv-alert--danger" role="alert">{{ message() }}</div>
    }
  `,
})
export class ErrorState {
  readonly error = input.required<unknown>();

  protected unauthorized(): boolean {
    return isUnauthorized(this.error());
  }

  protected message(): string {
    return messageOf(this.error());
  }
}
