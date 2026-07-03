import { HttpClient } from '@angular/common/http';
import { computed, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { call } from '../api/http';
import type { User } from '../api/types';
import { SessionStore } from './session';

/**
 * Caller identity and roles from `/api/v1/me`; only fetched when a session
 * exists. A 403 here is expected for blocked-but-authenticated users — they
 * keep `user === undefined` and their session-gated navigation.
 */
@Injectable({ providedIn: 'root' })
export class MeStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionStore);
  private readonly state = signal<{ user: User | undefined; pending: boolean }>({
    user: undefined,
    pending: false,
  });
  private generation = 0;

  readonly user = computed(() => this.state().user);
  readonly pending = computed(() => this.state().pending);

  // `admin` is a composite role in Keycloak that includes `moderator`, so an
  // admin token carries both strings — but stay defensive about it.
  readonly isAdmin = computed(() => this.user()?.roles.includes('admin') === true);
  readonly isModerator = computed(
    () => this.user()?.roles.includes('moderator') === true || this.isAdmin(),
  );

  constructor() {
    effect(() => {
      const authenticated = this.session.authenticated();
      untracked(() => {
        const generation = ++this.generation;
        if (!authenticated) {
          this.state.set({ user: undefined, pending: false });
          return;
        }
        this.state.set({ user: undefined, pending: true });
        call(this.http.get<User>('/api/v1/me'))
          .then((user) => {
            if (generation === this.generation) this.state.set({ user, pending: false });
          })
          .catch(() => {
            if (generation === this.generation) {
              this.state.set({ user: undefined, pending: false });
            }
          });
      });
    });
  }
}
