import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { flushAsync } from '../../testing/bundle-fetch';
import type { Bookmark, BookmarkInput, Report, TagList } from '../api/types';
import { SessionStore } from '../auth/session';
import { BookmarksApi, TagsStore } from './api';

const bookmark: Bookmark = {
  id: 'bookmark-1',
  url: 'https://example.com',
  title: 'Example',
  notes: 'Reference',
  tags: ['angular'],
  visibility: 'public',
  owner: 'demo',
  status: 'active',
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-01T00:00:00Z',
};

const input: BookmarkInput = {
  url: 'https://example.com',
  title: 'Example',
  notes: 'Reference',
  tags: ['angular'],
  visibility: 'public',
};

const report: Report = {
  id: 'report-1',
  bookmarkId: 'bookmark-1',
  reporter: 'demo',
  reason: 'broken-link',
  comment: '404',
  status: 'open',
  createdAt: '2026-07-01T00:00:00Z',
};

describe('BookmarksApi', () => {
  let api: BookmarksApi;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [BookmarksApi, provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(BookmarksApi);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  it('requests the v2 cursor-paginated bookmark list with filters intact', async () => {
    const response = { items: [bookmark], nextCursor: 'opaque-next' };
    const promise = api.listBookmarks(
      { tags: ['angular', 'tests'], q: 'coverage', visibility: 'public' },
      'opaque-current',
    );

    const request = controller.expectOne((req) => req.url === '/api/v2/bookmarks');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.getAll('tag')).toEqual(['angular', 'tests']);
    expect(request.request.params.get('q')).toBe('coverage');
    expect(request.request.params.get('visibility')).toBe('public');
    expect(request.request.params.get('cursor')).toBe('opaque-current');
    request.flush(response);

    await expect(promise).resolves.toEqual(response);
  });

  it('omits empty optional bookmark-list filters', async () => {
    const promise = api.listBookmarks({ tags: [], q: '' });

    const request = controller.expectOne((req) => req.url === '/api/v2/bookmarks');
    expect(request.request.params.has('tag')).toBe(false);
    expect(request.request.params.has('q')).toBe(false);
    expect(request.request.params.has('visibility')).toBe(false);
    expect(request.request.params.has('cursor')).toBe(false);
    request.flush({ items: [] });

    await expect(promise).resolves.toEqual({ items: [] });
  });

  it('uses the bookmark ownership endpoints for read, create, update, and delete', async () => {
    const get = api.getBookmark('bookmark-1');
    controller.expectOne('/api/v1/bookmarks/bookmark-1').flush(bookmark);
    await expect(get).resolves.toEqual(bookmark);

    const create = api.createBookmark(input);
    const createRequest = controller.expectOne('/api/v1/bookmarks');
    expect(createRequest.request.method).toBe('POST');
    expect(createRequest.request.body).toEqual(input);
    createRequest.flush(bookmark, { status: 201, statusText: 'Created' });
    await expect(create).resolves.toEqual(bookmark);

    const update = api.updateBookmark('bookmark-1', { ...input, title: 'Updated' });
    const updateRequest = controller.expectOne('/api/v1/bookmarks/bookmark-1');
    expect(updateRequest.request.method).toBe('PUT');
    expect(updateRequest.request.body).toMatchObject({ title: 'Updated' });
    updateRequest.flush({ ...bookmark, title: 'Updated' });
    await expect(update).resolves.toMatchObject({ title: 'Updated' });

    const remove = api.deleteBookmark('bookmark-1');
    const deleteRequest = controller.expectOne('/api/v1/bookmarks/bookmark-1');
    expect(deleteRequest.request.method).toBe('DELETE');
    deleteRequest.flush(null, { status: 204, statusText: 'No Content' });
    await expect(remove).resolves.toBeNull();
  });

  it('uses the reporter feedback-loop endpoints from SPEC rule 13', async () => {
    const list = api.listMyReports('open', 2);
    const listRequest = controller.expectOne((req) => req.url === '/api/v1/reports');
    expect(listRequest.request.params.get('status')).toBe('open');
    expect(listRequest.request.params.get('page')).toBe('2');
    listRequest.flush({ items: [report], page: 2, size: 20, totalItems: 1, totalPages: 3 });
    await expect(list).resolves.toMatchObject({ items: [report], page: 2 });

    const allReports = api.listMyReports('', 0);
    const allRequest = controller.expectOne((req) => req.url === '/api/v1/reports');
    expect(allRequest.request.params.has('status')).toBe(false);
    expect(allRequest.request.params.get('page')).toBe('0');
    allRequest.flush({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });
    await expect(allReports).resolves.toMatchObject({ items: [] });

    const update = api.updateMyReport('report-1', { reason: 'other', comment: 'fixed' });
    const updateRequest = controller.expectOne('/api/v1/reports/report-1');
    expect(updateRequest.request.method).toBe('PUT');
    expect(updateRequest.request.body).toEqual({ reason: 'other', comment: 'fixed' });
    updateRequest.flush({ ...report, reason: 'other', comment: 'fixed' });
    await expect(update).resolves.toMatchObject({ reason: 'other', comment: 'fixed' });

    const withdraw = api.withdrawReport('report-1');
    const withdrawRequest = controller.expectOne('/api/v1/reports/report-1');
    expect(withdrawRequest.request.method).toBe('DELETE');
    withdrawRequest.flush(null, { status: 204, statusText: 'No Content' });
    await expect(withdraw).resolves.toBeNull();
  });

  it('reports public bookmarks and loads caller tag counts', async () => {
    const createReport = api.reportBookmark('bookmark-1', { reason: 'spam', comment: 'ads' });
    const reportRequest = controller.expectOne('/api/v1/bookmarks/bookmark-1/reports');
    expect(reportRequest.request.method).toBe('POST');
    expect(reportRequest.request.body).toEqual({ reason: 'spam', comment: 'ads' });
    reportRequest.flush({ ...report, reason: 'spam', comment: 'ads' }, { status: 201, statusText: 'Created' });
    await expect(createReport).resolves.toMatchObject({ reason: 'spam', comment: 'ads' });

    const tags: TagList = { tags: [{ tag: 'angular', count: 3 }] };
    const listTags = api.listTags();
    controller.expectOne('/api/v1/tags').flush(tags);
    await expect(listTags).resolves.toEqual(tags);
  });
});

describe('TagsStore', () => {
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  it('loads tag counts when the gateway session is authenticated', async () => {
    const store = TestBed.inject(TagsStore);
    TestBed.tick();

    controller.expectOne('/auth/session').flush({ authenticated: true, username: 'demo' });
    await flushAsync();
    TestBed.tick();

    const request = controller.expectOne('/api/v1/tags');
    request.flush({ tags: [{ tag: 'angular', count: 2 }] });
    await flushAsync();

    expect(store.tags()).toEqual({ tags: [{ tag: 'angular', count: 2 }] });
  });

  it('clears tag counts when logout flips the session to anonymous', async () => {
    const store = TestBed.inject(TagsStore);
    const session = TestBed.inject(SessionStore);
    TestBed.tick();

    controller.expectOne('/auth/session').flush({ authenticated: true, username: 'demo' });
    await flushAsync();
    TestBed.tick();
    controller.expectOne('/api/v1/tags').flush({ tags: [{ tag: 'angular', count: 2 }] });
    await flushAsync();
    expect(store.tags()).toEqual({ tags: [{ tag: 'angular', count: 2 }] });

    const logout = session.logout();
    controller.expectOne('/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await logout;
    TestBed.tick();

    expect(store.tags()).toBeUndefined();
  });
});
