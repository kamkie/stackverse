import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { flushAsync, stubBundleFetch, type BundleFetchStub } from '../testing/bundle-fetch';
import { csrfInterceptor } from './api/csrf';
import { App } from './app';

describe('App', () => {
  let fetchStub: BundleFetchStub;
  let controller: HttpTestingController;
  let fixture: ComponentFixture<App>;

  beforeEach(async () => {
    localStorage.clear();
    fetchStub = stubBundleFetch();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([csrfInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(App);
  });

  afterEach(() => {
    fetchStub.restore();
  });

  async function boot(session: { authenticated: boolean; username?: string }, me?: object) {
    fixture.detectChanges();
    await flushAsync(); // bundle fetch resolves
    controller.expectOne('/auth/session').flush(session);
    await flushAsync();
    fixture.detectChanges();
    if (me !== undefined) {
      controller.expectOne('/api/v1/me').flush(me);
      await flushAsync();
      fixture.detectChanges();
    }
    await flushAsync();
    fixture.detectChanges();
  }

  function navTexts(): string[] {
    return Array.from(
      fixture.nativeElement.querySelectorAll('nav .sv-nav-link') as NodeListOf<HTMLElement>,
    ).map((el) => el.textContent?.trim() ?? '');
  }

  it('anonymous visitors get the public feed link and a login button', async () => {
    controller = TestBed.inject(HttpTestingController);
    await boot({ authenticated: false });
    expect(navTexts()).toEqual(['Public feed']);
    const login = fixture.nativeElement.querySelector('header a.sv-button') as HTMLAnchorElement;
    expect(login.textContent?.trim()).toBe('Log in');
    expect(login.getAttribute('href')).toBe('/auth/login');
    expect(fixture.nativeElement.querySelector('.sv-username')).toBeNull();
  });

  it('authenticated users see their navigation and username', async () => {
    controller = TestBed.inject(HttpTestingController);
    await boot({ authenticated: true, username: 'demo' }, { username: 'demo', roles: [] });
    expect(navTexts()).toEqual(['My bookmarks', 'My reports', 'Public feed']);
    expect(fixture.nativeElement.querySelector('.sv-username')?.textContent?.trim()).toBe('demo');
  });

  it('moderators additionally see the admin entry', async () => {
    controller = TestBed.inject(HttpTestingController);
    await boot(
      { authenticated: true, username: 'moderator' },
      { username: 'moderator', roles: ['moderator'] },
    );
    expect(navTexts()).toContain('Admin');
  });

  it('blocked users (403 from /me) keep their session-gated navigation', async () => {
    controller = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    await flushAsync();
    controller.expectOne('/auth/session').flush({ authenticated: true, username: 'demo' });
    await flushAsync();
    fixture.detectChanges();
    controller
      .expectOne('/api/v1/me')
      .flush({ title: 'Blocked' }, { status: 403, statusText: 'Forbidden' });
    await flushAsync();
    fixture.detectChanges();
    expect(navTexts()).toEqual(['My bookmarks', 'My reports', 'Public feed']);
  });

  it('switching the language swaps visible chrome without a reload', async () => {
    controller = TestBed.inject(HttpTestingController);
    await boot({ authenticated: false });
    const plButton = Array.from(
      fixture.nativeElement.querySelectorAll('.sv-lang-option') as NodeListOf<HTMLButtonElement>,
    ).find((el) => el.textContent?.trim() === 'PL');
    plButton?.click();
    await flushAsync();
    fixture.detectChanges();
    expect(navTexts()).toEqual(['Publiczne']);
    expect(localStorage.getItem('stackverse.lang')).toBe('pl');
  });

  it('the theme switcher persists the choice and stamps <html data-theme>', async () => {
    controller = TestBed.inject(HttpTestingController);
    await boot({ authenticated: false });
    const dark = Array.from(
      fixture.nativeElement.querySelectorAll('.sv-theme-option') as NodeListOf<HTMLButtonElement>,
    ).find((el) => el.textContent?.trim() === 'Dark');
    dark?.click();
    fixture.detectChanges();
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem('stackverse.theme')).toBe('dark');
    document.documentElement.removeAttribute('data-theme');
  });
});
