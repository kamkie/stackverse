import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { credentialsInterceptor } from './credentials';

describe('credentialsInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([credentialsInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    controller.verify();
  });

  it('sets withCredentials on GET requests', async () => {
    const response = firstValueFrom(http.get('/api/v1/bookmarks'));
    const request = controller.expectOne('/api/v1/bookmarks');
    expect(request.request.withCredentials).toBe(true);
    request.flush({ ok: true });
    await response;
  });

  it('sets withCredentials on state-changing requests', async () => {
    const response = firstValueFrom(http.post('/api/v1/bookmarks', {}));
    const request = controller.expectOne('/api/v1/bookmarks');
    expect(request.request.withCredentials).toBe(true);
    request.flush({ ok: true });
    await response;
  });

  it('sets withCredentials on the /auth/session read', async () => {
    const response = firstValueFrom(http.get('/auth/session'));
    const request = controller.expectOne('/auth/session');
    expect(request.request.withCredentials).toBe(true);
    request.flush({ authenticated: false });
    await response;
  });
});
