import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { ApiError } from '../api/problem';
import { SessionStore } from '../auth/session';
import { I18n } from '../i18n/i18n';
import { ErrorState } from './states';

describe('ErrorState', () => {
  let fixture: ComponentFixture<ErrorState>;
  let refresh: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    refresh = vi.fn().mockResolvedValue(undefined);
    await TestBed.configureTestingModule({
      imports: [ErrorState],
      providers: [
        { provide: I18n, useValue: { t: (key: string) => key } },
        { provide: SessionStore, useValue: { refresh } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ErrorState);
  });

  it('turns a 401 into a login prompt and refreshes the gateway session state', () => {
    fixture.componentRef.setInput('error', new ApiError(401, { title: 'Session expired' }));
    fixture.detectChanges();

    const login = fixture.nativeElement.querySelector('a') as HTMLAnchorElement;
    expect(login.getAttribute('href')).toBe('/auth/login');
    expect(login.textContent?.trim()).toBe('ui.action.login');
    expect(refresh).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('renders non-authentication failures without touching the session', () => {
    fixture.componentRef.setInput(
      'error',
      new ApiError(503, { title: 'Unavailable', detail: 'Please try again later.' }),
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent?.trim()).toBe(
      'Please try again later.',
    );
    expect(fixture.nativeElement.querySelector('a')).toBeNull();
    expect(refresh).not.toHaveBeenCalled();
  });
});
