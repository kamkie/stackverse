import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { csrfInterceptor } from './csrf';

function setCookie(value: string | null) {
  // jsdom cookies persist across specs — overwrite by expiring first
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  if (value !== null) document.cookie = `XSRF-TOKEN=${value}; path=/`;
}

describe('csrfInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([csrfInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    controller.verify();
    setCookie(null);
  });

  it('echoes the XSRF-TOKEN cookie as X-XSRF-TOKEN on state-changing requests', async () => {
    setCookie('token-1');
    const response = firstValueFrom(http.post('/api/v1/bookmarks', {}));
    const request = controller.expectOne('/api/v1/bookmarks');
    expect(request.request.headers.get('X-XSRF-TOKEN')).toBe('token-1');
    request.flush({ ok: true });
    await response;
  });

  it('leaves GET requests alone', async () => {
    setCookie('token-1');
    const response = firstValueFrom(http.get('/api/v1/bookmarks'));
    const request = controller.expectOne('/api/v1/bookmarks');
    expect(request.request.headers.has('X-XSRF-TOKEN')).toBe(false);
    request.flush({ ok: true });
    await response;
  });

  it('retries once with the re-read cookie when a 403 delivered a fresh token', async () => {
    setCookie(null); // no cookie yet — the 403 response is what sets it
    const response = firstValueFrom(http.post('/api/v1/bookmarks', {}));

    const first = controller.expectOne('/api/v1/bookmarks');
    expect(first.request.headers.has('X-XSRF-TOKEN')).toBe(false);
    setCookie('fresh-token'); // as the gateway's Set-Cookie would
    first.flush({ title: 'CSRF' }, { status: 403, statusText: 'Forbidden' });

    const retry = controller.expectOne('/api/v1/bookmarks');
    expect(retry.request.headers.get('X-XSRF-TOKEN')).toBe('fresh-token');
    retry.flush({ ok: true });
    await expect(response).resolves.toEqual({ ok: true });
  });

  it('does not retry a 403 when the cookie is unchanged (a real rejection)', async () => {
    setCookie('same-token');
    const response = firstValueFrom(http.post('/api/v1/bookmarks', {}));
    const request = controller.expectOne('/api/v1/bookmarks');
    request.flush({ title: 'CSRF' }, { status: 403, statusText: 'Forbidden' });
    await expect(response).rejects.toMatchObject({ status: 403 });
  });
});
