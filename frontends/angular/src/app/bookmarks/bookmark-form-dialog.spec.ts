import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { flushAsync, stubBundleFetch, type BundleFetchStub } from '../../testing/bundle-fetch';
import { BookmarkFormDialog } from './bookmark-form-dialog';

describe('BookmarkFormDialog', () => {
  let fetchStub: BundleFetchStub;
  let controller: HttpTestingController;
  let fixture: ComponentFixture<BookmarkFormDialog>;
  let saved: number;
  let closed: number;

  beforeEach(async () => {
    localStorage.clear();
    fetchStub = stubBundleFetch();
    await TestBed.configureTestingModule({
      imports: [BookmarkFormDialog],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    controller = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(BookmarkFormDialog);
    saved = 0;
    closed = 0;
    fixture.componentInstance.saved.subscribe(() => (saved += 1));
    fixture.componentInstance.closed.subscribe(() => (closed += 1));
    fixture.detectChanges();
    await flushAsync(); // bundle
    fixture.detectChanges();
  });

  afterEach(() => fetchStub.restore());

  function submit() {
    (fixture.nativeElement.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
  }

  it('renders validation problems on the matching fields, not as a toast', async () => {
    submit();
    const request = controller.expectOne('/api/v1/bookmarks');
    expect(request.request.method).toBe('POST');
    request.flush(
      {
        title: 'Validation failed',
        errors: [
          { field: 'url', messageKey: 'validation.url.required', message: 'URL is required.' },
        ],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await flushAsync();
    fixture.detectChanges();

    const error = fixture.nativeElement.querySelector('.sv-field-error') as HTMLElement;
    expect(error.textContent?.trim()).toBe('URL is required.');
    expect(saved).toBe(0);
    expect(closed).toBe(0); // the dialog stays open
  });

  it('emits saved and closed after a successful create', async () => {
    submit();
    controller
      .expectOne('/api/v1/bookmarks')
      .flush({ id: '1' }, { status: 201, statusText: 'Created' });
    await flushAsync();
    expect(saved).toBe(1);
    expect(closed).toBe(1);
  });

  it('shows the hidden-publish conflict alert on a 409', async () => {
    submit();
    controller
      .expectOne('/api/v1/bookmarks')
      .flush({ title: 'Conflict' }, { status: 409, statusText: 'Conflict' });
    await flushAsync();
    fixture.detectChanges();
    const alert = fixture.nativeElement.querySelector('.sv-alert--warning') as HTMLElement;
    expect(alert.textContent).toContain('hidden by moderation');
  });
});
