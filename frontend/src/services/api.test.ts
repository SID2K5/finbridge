import { AxiosError, type AxiosResponse } from 'axios';
import { describe, expect, it } from 'vitest';
import { ApiError, cleanParams, toApiError, unwrapPage } from './api';

function axiosFailure(status: number, data: unknown): AxiosError {
  const response = { status, data, statusText: '', headers: {}, config: {} } as AxiosResponse;
  return new AxiosError('Request failed', 'ERR_BAD_RESPONSE', undefined, undefined, response);
}

describe('toApiError', () => {
  it("reads the backend's error envelope", () => {
    const error = toApiError(
      axiosFailure(409, {
        error: { code: 'CONFLICT', message: 'Invoice BILL/1 already has a payment that succeeded or is still in progress' },
        correlationId: '3f1c9a52-6f0e-4a57-9d2b-1c8e7a4b0d11',
      }),
    );

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(409);
    expect(error.code).toBe('CONFLICT');
    expect(error.message).toContain('already has a payment');
    expect(error.correlationId).toBe('3f1c9a52-6f0e-4a57-9d2b-1c8e7a4b0d11');
  });

  it('keeps field errors for forms', () => {
    const error = toApiError(
      axiosFailure(400, {
        error: { code: 'VALIDATION_FAILED', message: 'One or more fields are invalid', fieldErrors: [{ field: 'note', message: 'a note is required' }] },
      }),
    );

    expect(error.fieldErrors).toEqual([{ field: 'note', message: 'a note is required' }]);
  });

  it('explains a backend that is down instead of showing a proxy error page', () => {
    expect(toApiError(axiosFailure(502, '<html>Bad gateway</html>')).code).toBe('BACKEND_DOWN');
    const offline = toApiError(new AxiosError('Network Error', 'ERR_NETWORK'));
    expect(offline.code).toBe('NETWORK_ERROR');
    expect(offline.status).toBe(0);
  });

  it('passes an ApiError through unchanged and wraps anything else', () => {
    const original = new ApiError('x', 422, 'BUSINESS_RULE_VIOLATION');
    expect(toApiError(original)).toBe(original);
    expect(toApiError(new Error('boom')).message).toBe('boom');
  });
});

describe('envelope helpers', () => {
  it('unwraps a paged list', () => {
    const page = unwrapPage({ data: [{ id: 1 }, { id: 2 }], meta: { page: 0, size: 20, totalElements: 2, totalPages: 1 } });

    expect(page.items).toHaveLength(2);
    expect(page.meta.totalElements).toBe(2);
  });

  it('treats a missing list as empty', () => {
    expect(unwrapPage({}).items).toEqual([]);
  });

  it('drops empty filter values but keeps zero and false', () => {
    expect(cleanParams({ status: '', invoiceId: undefined, q: null, page: 0, enabled: false, sort: 'name,asc' })).toEqual({
      page: 0,
      enabled: false,
      sort: 'name,asc',
    });
  });
});
