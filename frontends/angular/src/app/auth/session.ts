import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { call } from '../api/http';

/** Shape of `GET /auth/session` — the gateway contract (docs/ARCHITECTURE.md). */
export type Session = { authenticated: true; username: string } | { authenticated: false };

/** Login is a full-page redirect into the gateway's OIDC flow — never an XHR. */
export const LOGIN_URL = '/auth/login';

/**
 * Who the gateway believes is logged in. The SPA holds no auth state beyond
 * this answer — the session cookie itself is invisible to it.
 */
@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly http = inject(HttpClient);
  /** null = the initial fetch is still pending. */
  private readonly state = signal<Session | null>(null);

  readonly session = this.state.asReadonly();
  readonly pending = computed(() => this.state() === null);
  readonly authenticated = computed(() => this.state()?.authenticated === true);
  readonly username = computed(() => {
    const session = this.state();
    return session?.authenticated ? session.username : undefined;
  });

  constructor() {
    void this.refresh();
  }

  async refresh(): Promise<void> {
    try {
      this.state.set(await call(this.http.get<Session>('/auth/session')));
    } catch {
      this.state.set({ authenticated: false });
    }
  }

  /** `POST /auth/logout`, then flip to anonymous (the session is gone). */
  async logout(): Promise<void> {
    try {
      await call(this.http.post('/auth/logout', null));
    } finally {
      this.state.set({ authenticated: false });
    }
  }
}
