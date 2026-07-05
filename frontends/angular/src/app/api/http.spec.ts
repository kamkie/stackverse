import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { ApiError } from './problem';
import { call, params } from './http';

describe('call', () => {
  it('resolves HttpClient observables', async () => {
    await expect(call(of({ ok: true }))).resolves.toEqual({ ok: true });
  });

  it('normalizes HttpClient failures into ApiError', async () => {
    await expect(
      call(throwError(() => new HttpErrorResponse({ status: 403, error: { title: 'Forbidden' } }))),
    ).rejects.toBeInstanceOf(ApiError);
  });
});

describe('params', () => {
  it('drops empty optional values while keeping meaningful zeroes and arrays', () => {
    expect(
      params({
        empty: '',
        missing: undefined,
        none: [],
        page: 0,
        status: 'open',
        tag: ['angular', 'tests'],
      }),
    ).toEqual({
      page: 0,
      status: 'open',
      tag: ['angular', 'tests'],
    });
  });
});
