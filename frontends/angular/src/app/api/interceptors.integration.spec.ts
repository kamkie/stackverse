import { HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { appConfig } from '../app.config';

function setCookie(value: string | null) {
  // jsdom cookies persist across specs — overwrite by expiring first
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  if (value !== null) document.cookie = `XSRF-TOKEN=${value}; path=/`;
}

/**
 * Guards the *real* `appConfig` interceptor list and its order, not a
 * re-declared copy: spreading `appConfig.providers` and adding
 * `provideHttpClientTesting()` swaps only the HTTP backend, so the configured
 * `credentialsInterceptor` + `csrfInterceptor` still run. This catches a future
 * edit that drops or reorders either interceptor — which the per-interceptor
 * specs would not.
 */
describe('appConfig HTTP interceptors', () => {
  let http: HttpClient;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    controller.verify();
    setCookie(null);
  });

  it('sends withCredentials on a GET (credentialsInterceptor is wired)', async () => {
    const response = firstValueFrom(http.get('/api/v1/tags'));
    const request = controller.expectOne('/api/v1/tags');
    expect(request.request.withCredentials).toBe(true);
    request.flush({ tags: [] });
    await response;
  });

  it('sends withCredentials and the CSRF header together on a state-changing call', async () => {
    setCookie('token-1');
    const response = firstValueFrom(http.post('/api/v1/bookmarks', {}));
    const request = controller.expectOne('/api/v1/bookmarks');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.headers.get('X-XSRF-TOKEN')).toBe('token-1');
    request.flush({ ok: true });
    await response;
  });

  it('keeps withCredentials on the CSRF 403-retry (interceptor order holds)', async () => {
    setCookie(null); // no cookie yet — the 403 response is what sets it
    const response = firstValueFrom(http.post('/api/v1/bookmarks', {}));

    const first = controller.expectOne('/api/v1/bookmarks');
    expect(first.request.withCredentials).toBe(true);
    setCookie('fresh-token'); // as the gateway's Set-Cookie would
    first.flush({ title: 'CSRF' }, { status: 403, statusText: 'Forbidden' });

    const retry = controller.expectOne('/api/v1/bookmarks');
    expect(retry.request.withCredentials).toBe(true);
    expect(retry.request.headers.get('X-XSRF-TOKEN')).toBe('fresh-token');
    retry.flush({ ok: true });
    await expect(response).resolves.toEqual({ ok: true });
  });
});
