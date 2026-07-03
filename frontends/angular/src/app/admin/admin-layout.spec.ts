import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { flushAsync, stubBundleFetch, type BundleFetchStub } from '../../testing/bundle-fetch';
import { AdminLayout } from './admin-layout';

describe('AdminLayout', () => {
  let fetchStub: BundleFetchStub;
  let controller: HttpTestingController;
  let fixture: ComponentFixture<AdminLayout>;

  beforeEach(async () => {
    localStorage.clear();
    fetchStub = stubBundleFetch();
    await TestBed.configureTestingModule({
      imports: [AdminLayout],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    controller = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdminLayout);
  });

  afterEach(() => fetchStub.restore());

  async function boot(roles: string[] | null) {
    fixture.detectChanges();
    await flushAsync();
    controller
      .expectOne('/auth/session')
      .flush(roles === null ? { authenticated: false } : { authenticated: true, username: 'u' });
    await flushAsync();
    fixture.detectChanges();
    if (roles !== null) {
      controller.expectOne('/api/v1/me').flush({ username: 'u', roles });
      await flushAsync();
      fixture.detectChanges();
    }
  }

  function navLinks(): string[] {
    return Array.from(
      fixture.nativeElement.querySelectorAll('nav a') as NodeListOf<HTMLElement>,
    ).map((el) => el.textContent?.trim() ?? '');
  }

  it('rejects plain users with a 403 alert', async () => {
    await boot([]);
    const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    expect(alert.textContent?.trim()).toBe('403');
  });

  it('moderators see dashboard and reports only', async () => {
    await boot(['moderator']);
    expect(navLinks()).toEqual(['Dashboard', 'Reports']);
  });

  it('admins see the full backoffice navigation', async () => {
    await boot(['moderator', 'admin']);
    expect(navLinks()).toEqual(['Dashboard', 'Reports', 'Users', 'Audit log', 'Messages']);
  });

  it('anonymous visitors are offered login', async () => {
    await boot(null);
    // LoginPrompt re-checks the session — answer the second probe too
    controller.expectOne('/auth/session').flush({ authenticated: false });
    await flushAsync();
    fixture.detectChanges();
    const login = fixture.nativeElement.querySelector('a.sv-button') as HTMLAnchorElement;
    expect(login.getAttribute('href')).toBe('/auth/login');
  });
});
