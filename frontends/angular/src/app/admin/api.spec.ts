import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type {
  AdminStats,
  AuditEntry,
  Message,
  MessageInput,
  Report,
  UserAccount,
} from '../api/types';
import { AdminApi } from './api';

const stats: AdminStats = {
  totals: {
    users: 4,
    bookmarks: 8,
    publicBookmarks: 3,
    hiddenBookmarks: 1,
    openReports: 2,
  },
  daily: [{ date: '2026-07-01', bookmarksCreated: 1, activeUsers: 2 }],
  topTags: [{ tag: 'angular', count: 5 }],
};

const report: Report = {
  id: 'report-1',
  bookmarkId: 'bookmark-1',
  reporter: 'demo',
  reason: 'spam',
  status: 'open',
  createdAt: '2026-07-01T00:00:00Z',
};

const user: UserAccount = {
  username: 'demo',
  firstSeen: '2026-07-01T00:00:00Z',
  lastSeen: '2026-07-02T00:00:00Z',
  status: 'active',
  bookmarkCount: 2,
};

const auditEntry: AuditEntry = {
  id: 'audit-1',
  actor: 'admin',
  action: 'report.resolved',
  targetType: 'report',
  targetId: 'report-1',
  detail: { resolution: 'dismissed' },
  createdAt: '2026-07-01T00:00:00Z',
};

const messageInput: MessageInput = {
  key: 'ui.test.message',
  language: 'en',
  text: 'Test message',
  description: 'Used by tests',
};

const message: Message = {
  id: 'message-1',
  ...messageInput,
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-01T00:00:00Z',
};

describe('AdminApi', () => {
  let api: AdminApi;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AdminApi, provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(AdminApi);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  it('loads dashboard stats from the admin stats endpoint', async () => {
    const promise = api.getStats();

    const request = controller.expectOne('/api/v1/admin/stats');
    expect(request.request.method).toBe('GET');
    request.flush(stats);

    await expect(promise).resolves.toEqual(stats);
  });

  it('lists and resolves moderation reports with the contract payload', async () => {
    const list = api.listReports('open', 1);
    const listRequest = controller.expectOne((req) => req.url === '/api/v1/admin/reports');
    expect(listRequest.request.method).toBe('GET');
    expect(listRequest.request.params.get('status')).toBe('open');
    expect(listRequest.request.params.get('page')).toBe('1');
    listRequest.flush({ items: [report], page: 1, size: 20, totalItems: 1, totalPages: 2 });
    await expect(list).resolves.toMatchObject({ items: [report], page: 1 });

    const resolve = api.resolveReport('report-1', { resolution: 'dismissed', note: 'Not spam' });
    const resolveRequest = controller.expectOne('/api/v1/admin/reports/report-1');
    expect(resolveRequest.request.method).toBe('PUT');
    expect(resolveRequest.request.body).toEqual({ resolution: 'dismissed', note: 'Not spam' });
    resolveRequest.flush({ ...report, status: 'dismissed', resolutionNote: 'Not spam' });
    await expect(resolve).resolves.toMatchObject({ status: 'dismissed' });
  });

  it('lists users and URL-encodes usernames in status changes', async () => {
    const list = api.listUsers('de', 0);
    const listRequest = controller.expectOne((req) => req.url === '/api/v1/admin/users');
    expect(listRequest.request.params.get('q')).toBe('de');
    expect(listRequest.request.params.get('page')).toBe('0');
    listRequest.flush({ items: [user], page: 0, size: 20, totalItems: 1, totalPages: 1 });
    await expect(list).resolves.toMatchObject({ items: [user] });

    const update = api.setUserStatus('demo/user', { status: 'blocked', reason: 'abuse' });
    const updateRequest = controller.expectOne('/api/v1/admin/users/demo%2Fuser/status');
    expect(updateRequest.request.method).toBe('PUT');
    expect(updateRequest.request.body).toEqual({ status: 'blocked', reason: 'abuse' });
    updateRequest.flush({ ...user, username: 'demo/user', status: 'blocked', blockedReason: 'abuse' });
    await expect(update).resolves.toMatchObject({ status: 'blocked', blockedReason: 'abuse' });
  });

  it('builds audit-log filters without sending empty values', async () => {
    const promise = api.listAuditEntries({
      actor: '',
      action: 'report.resolved',
      from: '2026-07-01T00:00:00Z',
      to: undefined,
      page: 2,
    });

    const request = controller.expectOne((req) => req.url === '/api/v1/admin/audit-log');
    expect(request.request.params.has('actor')).toBe(false);
    expect(request.request.params.get('action')).toBe('report.resolved');
    expect(request.request.params.get('from')).toBe('2026-07-01T00:00:00Z');
    expect(request.request.params.has('to')).toBe(false);
    expect(request.request.params.get('page')).toBe('2');
    request.flush({ items: [auditEntry], page: 2, size: 20, totalItems: 1, totalPages: 3 });

    await expect(promise).resolves.toMatchObject({ items: [auditEntry], page: 2 });
  });

  it('uses runtime message-management endpoints for admin CRUD', async () => {
    const list = api.listMessages({ q: 'test', language: 'en', page: 3 });
    const listRequest = controller.expectOne((req) => req.url === '/api/v1/messages');
    expect(listRequest.request.params.get('q')).toBe('test');
    expect(listRequest.request.params.get('language')).toBe('en');
    expect(listRequest.request.params.get('page')).toBe('3');
    listRequest.flush({ items: [message], page: 3, size: 20, totalItems: 1, totalPages: 4 });
    await expect(list).resolves.toMatchObject({ items: [message], page: 3 });

    const create = api.createMessage(messageInput);
    const createRequest = controller.expectOne('/api/v1/messages');
    expect(createRequest.request.method).toBe('POST');
    expect(createRequest.request.body).toEqual(messageInput);
    createRequest.flush(message, { status: 201, statusText: 'Created' });
    await expect(create).resolves.toEqual(message);

    const update = api.updateMessage('message-1', { ...messageInput, text: 'Updated' });
    const updateRequest = controller.expectOne('/api/v1/messages/message-1');
    expect(updateRequest.request.method).toBe('PUT');
    expect(updateRequest.request.body).toMatchObject({ text: 'Updated' });
    updateRequest.flush({ ...message, text: 'Updated' });
    await expect(update).resolves.toMatchObject({ text: 'Updated' });

    const remove = api.deleteMessage('message-1');
    const deleteRequest = controller.expectOne('/api/v1/messages/message-1');
    expect(deleteRequest.request.method).toBe('DELETE');
    deleteRequest.flush(null, { status: 204, statusText: 'No Content' });
    await expect(remove).resolves.toBeNull();
  });
});
