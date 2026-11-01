import type { ApiErrorBody } from './types';

/** Typed error raised for every non-2xx response; mirrors api/ApiError.java. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly errors: string[];
  readonly details: Record<string, unknown>;

  constructor(status: number, body: Partial<ApiErrorBody> | null, fallback?: string) {
    super(body?.message ?? fallback ?? `HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code =
      body?.code ?? (status === 401 ? 'UNAUTHORIZED' : status === 403 ? 'FORBIDDEN' : 'HTTP_' + status);
    this.errors = body?.errors ?? [];
    this.details = body?.details ?? {};
  }

  /** `errors[]` entries shaped "field: message" (WebExchangeBindException) keyed by field. */
  fieldErrors(): Record<string, string> {
    const out: Record<string, string> = {};
    for (const e of this.errors) {
      const i = e.indexOf(': ');
      if (i > 0) out[e.slice(0, i)] = e.slice(i + 2);
    }
    return out;
  }

  get isForbidden() {
    return this.status === 403;
  }
  get isUnauthorized() {
    return this.status === 401;
  }
}

export class NetworkError extends Error {
  constructor(cause: unknown) {
    super('Governance API is unreachable');
    this.name = 'NetworkError';
    this.cause = cause;
  }
}

type TokenProvider = () => string | null | undefined;
let tokenProvider: TokenProvider = () => null;
let unauthorizedHandler: (() => void) | null = null;

/** Installed once by AuthProvider; keeps the API layer independent of React. */
export function setTokenProvider(p: TokenProvider) {
  tokenProvider = p;
}
export function setUnauthorizedHandler(h: (() => void) | null) {
  unauthorizedHandler = h;
}

export type Query = Record<string, string | number | boolean | null | undefined>;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  query?: Query;
  signal?: AbortSignal;
  /** Base URL for tests; defaults to same-origin (nginx/vite proxy `/api`). */
  baseUrl?: string;
}

export function buildUrl(path: string, query?: Query, baseUrl = ''): string {
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query ?? {})) {
    if (v === undefined || v === null || v === '') continue;
    params.set(k, String(v));
  }
  const qs = params.toString();
  return baseUrl + path + (qs ? `?${qs}` : '');
}

export async function apiFetch<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = tokenProvider();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';

  let res: Response;
  try {
    res = await fetch(buildUrl(path, opts.query, opts.baseUrl), {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      signal: opts.signal,
      credentials: 'omit',
    });
  } catch (e) {
    throw new NetworkError(e);
  }

  if (res.ok) {
    if (res.status === 204) return undefined as T;
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  let body: Partial<ApiErrorBody> | null = null;
  try {
    body = (await res.json()) as Partial<ApiErrorBody>;
  } catch {
    body = null;
  }
  const error = new ApiError(res.status, body, res.statusText);
  if (res.status === 401) unauthorizedHandler?.();
  throw error;
}

/** Human-readable message for toasts. */
export function describeError(e: unknown): string {
  if (e instanceof ApiError) {
    const extra = e.errors.length ? ` (${e.errors.join('; ')})` : '';
    switch (e.code) {
      case 'FORBIDDEN':
        return 'Not allowed: ' + e.message;
      case 'STATE_CONFLICT':
        return 'Conflict: ' + e.message;
      case 'POLICY_ENGINE_UNAVAILABLE':
        return 'Policy engine unavailable: ' + e.message;
      default:
        return e.message + extra;
    }
  }
  if (e instanceof NetworkError) return e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}
