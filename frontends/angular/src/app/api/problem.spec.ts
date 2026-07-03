import { HttpErrorResponse } from '@angular/common/http';
import {
  ApiError,
  fieldErrorFor,
  isConflict,
  isUnauthorized,
  messageOf,
  toApiError,
} from './problem';
import type { Problem } from './types';

const problem: Problem = {
  title: 'Validation failed',
  status: 400,
  detail: 'One or more fields are invalid.',
  errors: [
    { field: 'url', messageKey: 'validation.url.required', message: 'URL is required.' },
    { field: 'tags[2]', messageKey: 'validation.tag.invalid', message: 'Bad tag.' },
  ],
};

describe('ApiError', () => {
  it('prefers the problem detail as its message', () => {
    expect(new ApiError(400, problem).message).toBe('One or more fields are invalid.');
    expect(new ApiError(400, { title: 'Nope' }).message).toBe('Nope');
    expect(new ApiError(502).message).toBe('HTTP 502');
  });

  it('classifies statuses', () => {
    expect(isUnauthorized(new ApiError(401))).toBe(true);
    expect(isUnauthorized(new ApiError(403))).toBe(false);
    expect(isConflict(new ApiError(409))).toBe(true);
    expect(isConflict(new Error('409'))).toBe(false);
  });
});

describe('toApiError', () => {
  it('carries the problem document out of an HttpErrorResponse', () => {
    const error = toApiError(new HttpErrorResponse({ status: 400, error: problem }));
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(400);
    expect(error.fieldErrors).toHaveLength(2);
  });

  it('handles non-JSON error bodies', () => {
    const error = toApiError(new HttpErrorResponse({ status: 502, error: 'Bad Gateway' }));
    expect(error.problem).toBeUndefined();
    expect(error.message).toBe('HTTP 502');
  });

  it('rethrows non-HTTP errors', () => {
    expect(() => toApiError(new TypeError('boom'))).toThrow(TypeError);
  });
});

describe('fieldErrorFor', () => {
  const error = new ApiError(400, problem);

  it('matches exact fields', () => {
    expect(fieldErrorFor(error, 'url')?.messageKey).toBe('validation.url.required');
  });

  it('matches indexed and nested paths', () => {
    expect(fieldErrorFor(error, 'tags')?.messageKey).toBe('validation.tag.invalid');
  });

  it('returns nothing for other errors', () => {
    expect(fieldErrorFor(new Error('x'), 'url')).toBeUndefined();
    expect(fieldErrorFor(error, 'title')).toBeUndefined();
  });
});

describe('messageOf', () => {
  it('reads Error messages and stringifies the rest', () => {
    expect(messageOf(new Error('boom'))).toBe('boom');
    expect(messageOf('plain')).toBe('plain');
  });
});
