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
import type { AuditEntry } from '../api/types';
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

  it('renders immutable audit entries with a localized timestamp and shortened target id', async () => {
    const entry: AuditEntry = {
      id: 'audit-1',
      actor: 'moderator',
      action: 'report.resolved',
      targetType: 'report',
      targetId: '12345678-abcd-efgh',
      detail: { resolution: 'dismissed' },
      createdAt: '2026-07-02T10:30:00Z',
    };
    const component = fixture.componentInstance as unknown as {
      audit: { reload(): void };
    };
    component.audit.reload();
    controller.expectOne(isAuditRequest).flush({
      items: [entry],
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
    });
    await flushAsync();
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('tbody tr') as HTMLTableRowElement;
    expect(row.textContent).toContain('moderator');
    expect(row.textContent).toContain('report.resolved');
    expect(row.textContent).toContain('report/12345678');
    expect(row.querySelector('time')?.getAttribute('datetime')).toBe('2026-07-02T10:30:00Z');
  });

  it('renders an operational failure instead of a stale table', async () => {
    const component = fixture.componentInstance as unknown as {
      audit: { reload(): void };
    };
    component.audit.reload();
    controller
      .expectOne(isAuditRequest)
      .flush(
        { title: 'Unavailable', detail: 'Audit service unavailable.' },
        { status: 503, statusText: 'Service Unavailable' },
      );
    await flushAsync();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent?.trim()).toBe(
      'Audit service unavailable.',
    );
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
  });
});
