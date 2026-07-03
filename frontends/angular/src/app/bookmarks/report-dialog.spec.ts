import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { flushAsync, stubBundleFetch, type BundleFetchStub } from '../../testing/bundle-fetch';
import type { Bookmark } from '../api/types';
import { ToastStore } from '../core/toast';
import { ReportDialog } from './report-dialog';

const BOOKMARK: Bookmark = {
  id: 'b1',
  owner: 'carol',
  url: 'https://example.com/',
  title: 'Example',
  tags: [],
  visibility: 'public',
  status: 'active',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

describe('ReportDialog', () => {
  let fetchStub: BundleFetchStub;
  let controller: HttpTestingController;
  let fixture: ComponentFixture<ReportDialog>;
  let toast: ToastStore;
  let reported: string[];
  let closed: number;

  beforeEach(async () => {
    localStorage.clear();
    fetchStub = stubBundleFetch();
    await TestBed.configureTestingModule({
      imports: [ReportDialog],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    controller = TestBed.inject(HttpTestingController);
    toast = TestBed.inject(ToastStore);
    fixture = TestBed.createComponent(ReportDialog);
    fixture.componentRef.setInput('bookmark', BOOKMARK);
    reported = [];
    closed = 0;
    fixture.componentInstance.reported.subscribe((id) => reported.push(id));
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

  it('confirms a successful submit: toast, reported, closed', async () => {
    submit();
    const request = controller.expectOne('/api/v1/bookmarks/b1/reports');
    expect(request.request.method).toBe('POST');
    request.flush({ id: 'r1' }, { status: 201, statusText: 'Created' });
    await flushAsync();

    expect(reported).toEqual(['b1']);
    expect(closed).toBe(1);
    expect(toast.items().map((t) => t.message)).toEqual(['Report submitted — thank you.']);
  });

  it('treats a 409 duplicate as confirmation, not an error (SPEC rule 13)', async () => {
    submit();
    controller.expectOne('/api/v1/bookmarks/b1/reports').flush(
      { title: 'Conflict', detail: 'You already have an open report on this bookmark.' },
      { status: 409, statusText: 'Conflict' },
    );
    await flushAsync();
    fixture.detectChanges();

    expect(reported).toEqual(['b1']);
    expect(closed).toBe(1);
    expect(fixture.nativeElement.querySelector('.sv-alert--warning')).toBeNull();
    expect(toast.items().map((t) => t.message)).toEqual([
      'Already reported — your earlier report is still open.',
    ]);
  });

  it('renders validation problems on the matching field and stays open', async () => {
    submit();
    controller.expectOne('/api/v1/bookmarks/b1/reports').flush(
      {
        title: 'Validation failed',
        errors: [
          {
            field: 'comment',
            messageKey: 'validation.report.comment.too-long',
            message: 'Report comment must be at most 1000 characters.',
          },
        ],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await flushAsync();
    fixture.detectChanges();

    const error = fixture.nativeElement.querySelector('.sv-field-error') as HTMLElement;
    expect(error.textContent?.trim()).toBe('Report comment must be at most 1000 characters.');
    expect(reported).toEqual([]);
    expect(closed).toBe(0); // the dialog stays open
  });
});
