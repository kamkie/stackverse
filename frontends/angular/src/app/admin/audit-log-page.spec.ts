// Audit log date filters: the From/To inputs select whole local calendar
// days, so the query maps From to the first millisecond of the selected day
// and To to the last — a "To: today" filter must include today's entries.
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  type TestRequest,
} from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { flushAsync, stubBundleFetch, type BundleFetchStub } from '../../testing/bundle-fetch';
import { AuditLogPage } from './audit-log-page';

const EMPTY_PAGE = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 };

const isAuditRequest = (req: { url: string }) => req.url === '/api/v1/admin/audit-log';

describe('AuditLogPage', () => {
  let fetchStub: BundleFetchStub;
  let controller: HttpTestingController;
  let fixture: ComponentFixture<AuditLogPage>;
  let bootRequest: TestRequest;

  beforeEach(async () => {
    localStorage.clear();
    fetchStub = stubBundleFetch();
    await TestBed.configureTestingModule({
      imports: [AuditLogPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    controller = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AuditLogPage);
    fixture.detectChanges();
    await flushAsync();
    bootRequest = controller.expectOne(isAuditRequest);
    bootRequest.flush(EMPTY_PAGE);
    await flushAsync();
    fixture.detectChanges();
  });

  afterEach(() => {
    controller.verify();
    fetchStub.restore();
  });

  async function setDate(name: string, value: string): Promise<void> {
    const input = fixture.nativeElement.querySelector(`input[name="${name}"]`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await flushAsync();
  }

  it('omits from/to until a date is picked', () => {
    expect(bootRequest.request.params.has('from')).toBe(false);
    expect(bootRequest.request.params.has('to')).toBe(false);
  });

  it('sends From as the local start and To as the local end of the selected day', async () => {
    await setDate('from', '2026-06-01');
    const fromRequest = controller.expectOne(isAuditRequest);
    expect(fromRequest.request.params.get('from')).toBe(
      new Date('2026-06-01T00:00:00').toISOString(),
    );
    expect(fromRequest.request.params.has('to')).toBe(false);
    fromRequest.flush(EMPTY_PAGE);
    await flushAsync();

    await setDate('to', '2026-06-01');
    const toRequest = controller.expectOne(isAuditRequest);
    // To covers the whole selected day, not just its first instant — down to
    // the backend's microsecond timestamp precision.
    expect(toRequest.request.params.get('to')).toBe(
      new Date('2026-06-01T23:59:59.999').toISOString().replace('.999Z', '.999999Z'),
    );
    expect(toRequest.request.params.get('from')).toBe(
      new Date('2026-06-01T00:00:00').toISOString(),
    );
    toRequest.flush(EMPTY_PAGE);
    await flushAsync();
  });
});
