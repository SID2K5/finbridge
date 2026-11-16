import axios, { AxiosError, type AxiosRequestConfig, type AxiosResponse } from 'axios';
import type { Page, PageMeta } from '../types/api';

/**
 * The one HTTP client. Every backend answer has the same envelope:
 *   success  { data, meta?, correlationId, timestamp }
 *   error    { error: { code, message, fieldErrors? }, correlationId, timestamp }
 * The helpers below unwrap it, so pages work with plain data and one error type.
 */

export interface FieldError {
  field: string;
  message: string;
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code: string,
    public readonly fieldErrors: FieldError[] = [],
    public readonly correlationId?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

interface Envelope<T> {
  data?: T;
  meta?: PageMeta;
  error?: { code: string; message: string; fieldErrors?: FieldError[] };
  correlationId?: string;
}

/** Turns anything axios can throw into an ApiError with a message fit to show a person. */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error;
  }
  if (axios.isAxiosError(error)) {
    const axiosError = error as AxiosError<Envelope<unknown>>;
    const response = axiosError.response;
    if (response) {
      const body = response.data;
      if (body && typeof body === 'object' && body.error) {
        return new ApiError(
          body.error.message,
          response.status,
          body.error.code,
          body.error.fieldErrors ?? [],
          body.correlationId,
        );
      }
      if (response.status >= 502 && response.status <= 504) {
        return new ApiError('The FinBridge backend is not reachable. Is it running?', response.status, 'BACKEND_DOWN');
      }
      return new ApiError(`The request failed (HTTP ${response.status}).`, response.status, 'HTTP_ERROR');
    }
    return new ApiError('The FinBridge backend is not reachable. Check your connection.', 0, 'NETWORK_ERROR');
  }
  return new ApiError(error instanceof Error ? error.message : 'Something went wrong.', 0, 'UNKNOWN');
}

export function unwrapPage<T>(body: Envelope<T[]>): Page<T> {
  return {
    items: body.data ?? [],
    meta: body.meta ?? { page: 0, size: body.data?.length ?? 0, totalElements: body.data?.length ?? 0, totalPages: 1 },
  };
}

/** Drops empty filter values so they are not sent as "status=" and rejected by the backend. */
export function cleanParams(params: Record<string, unknown> = {}): Record<string, unknown> {
  const cleaned: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      cleaned[key] = value;
    }
  }
  return cleaned;
}

// ---- token handling: the AuthContext is the owner; this module only reads what it is given ----

let accessToken: string | null = null;
let onUnauthorized: () => void = () => undefined;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function setUnauthorizedHandler(handler: () => void): void {
  onUnauthorized = handler;
}

const http = axios.create({ baseURL: '/api', timeout: 60_000 });

http.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.set('Authorization', `Bearer ${accessToken}`);
  }
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const apiError = toApiError(error);
    const url = axios.isAxiosError(error) ? error.config?.url ?? '' : '';
    // An expired or revoked session: sign out. A wrong password on the login form is not that.
    if (apiError.status === 401 && !url.includes('/auth/login')) {
      onUnauthorized();
    }
    return Promise.reject(apiError);
  },
);

export async function getData<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const response = await http.get<Envelope<T>>(url, { params: cleanParams(params) });
  return response.data.data as T;
}

export async function getPage<T>(url: string, params?: Record<string, unknown>): Promise<Page<T>> {
  const response = await http.get<Envelope<T[]>>(url, { params: cleanParams(params) });
  return unwrapPage(response.data);
}

export async function send<T>(
  method: 'post' | 'put' | 'patch',
  url: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  const response = await http.request<Envelope<T>>({ method, url, data: body, ...config });
  return response.data?.data as T;
}

/** Like send, for the few calls where a response header matters (Idempotent-Replay). */
export async function sendWithResponse<T>(
  method: 'post' | 'put' | 'patch',
  url: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<{ data: T; response: AxiosResponse }> {
  const response = await http.request<Envelope<T>>({ method, url, data: body, ...config });
  return { data: response.data?.data as T, response };
}
