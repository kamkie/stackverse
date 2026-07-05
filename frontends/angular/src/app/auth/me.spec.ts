import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { flushAsync } from '../../testing/bundle-fetch';
import { MeStore } from './me';
import { SessionStore } from './session';

describe('MeStore', () => {
  let authenticated: ReturnType<typeof signal<boolean>>;
  let controller: HttpTestingController;

  beforeEach(() => {
    authenticated = signal(false);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SessionStore, useValue: { authenticated: authenticated.asReadonly() } },
      ],
    });
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    controller.verify();
    vi.restoreAllMocks();
  });

  it('loads caller roles and treats admin as moderator defensively', async () => {
    const me = TestBed.inject(MeStore);
    TestBed.tick();
    expect(controller.match('/api/v1/me')).toHaveLength(0);

    authenticated.set(true);
    TestBed.tick();
    controller.expectOne('/api/v1/me').flush({ username: 'admin', roles: ['admin'] });
    await flushAsync();

    expect(me.user()?.username).toBe('admin');
    expect(me.isAdmin()).toBe(true);
    expect(me.isModerator()).toBe(true);
    expect(me.pending()).toBe(false);
  });

  it('keeps blocked users anonymous without logging the expected 403', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const me = TestBed.inject(MeStore);

    authenticated.set(true);
    TestBed.tick();
    controller
      .expectOne('/api/v1/me')
      .flush({ title: 'Blocked' }, { status: 403, statusText: 'Forbidden' });
    await flushAsync();

    expect(me.user()).toBeUndefined();
    expect(me.isAdmin()).toBe(false);
    expect(me.isModerator()).toBe(false);
    expect(me.pending()).toBe(false);
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('logs unexpected /me failures instead of swallowing them as anonymous auth', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const me = TestBed.inject(MeStore);

    authenticated.set(true);
    TestBed.tick();
    controller
      .expectOne('/api/v1/me')
      .flush({ title: 'Gateway unavailable' }, { status: 503, statusText: 'Unavailable' });
    await flushAsync();

    expect(me.user()).toBeUndefined();
    expect(me.pending()).toBe(false);
    expect(errorSpy).toHaveBeenCalledTimes(1);
  });
});
