import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { flushAsync } from '../../testing/bundle-fetch';
import { SessionStore } from './session';

describe('SessionStore', () => {
  let store: SessionStore;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [SessionStore, provideHttpClient(), provideHttpClientTesting()],
    });
    controller = TestBed.inject(HttpTestingController);
    store = TestBed.inject(SessionStore);
  });

  afterEach(() => controller.verify());

  it('derives authentication and username only from the gateway session response', async () => {
    expect(store.pending()).toBe(true);
    controller.expectOne('/auth/session').flush({ authenticated: true, username: 'demo' });
    await flushAsync();

    expect(store.pending()).toBe(false);
    expect(store.authenticated()).toBe(true);
    expect(store.username()).toBe('demo');
  });

  it('fails closed to anonymous when the session endpoint is unavailable', async () => {
    controller
      .expectOne('/auth/session')
      .flush({ title: 'Unavailable' }, { status: 503, statusText: 'Service Unavailable' });
    await flushAsync();

    expect(store.session()).toEqual({ authenticated: false });
    expect(store.authenticated()).toBe(false);
    expect(store.username()).toBeUndefined();
  });

  it('clears local session state even when gateway logout fails', async () => {
    controller.expectOne('/auth/session').flush({ authenticated: true, username: 'demo' });
    await flushAsync();

    const logout = store.logout();
    const request = controller.expectOne('/auth/logout');
    expect(request.request.method).toBe('POST');
    request.flush(
      { title: 'Unavailable', detail: 'Logout failed.' },
      { status: 503, statusText: 'Service Unavailable' },
    );

    await expect(logout).rejects.toMatchObject({ status: 503, message: 'Logout failed.' });
    expect(store.session()).toEqual({ authenticated: false });
  });
});
