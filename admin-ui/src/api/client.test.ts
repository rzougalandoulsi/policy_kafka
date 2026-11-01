import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { apiError } from '@/test/handlers';
import {
  ApiError,
  apiFetch,
  buildUrl,
  describeError,
  setTokenProvider,
  setUnauthorizedHandler,
} from './client';

describe('apiFetch', () => {
  it('builds query strings and skips empty values', () => {
    expect(buildUrl('/x', { a: 1, b: '', c: undefined, d: false, e: 'z' })).toBe('/x?a=1&d=false&e=z');
  });

  it('sends the bearer token and parses JSON', async () => {
    setTokenProvider(() => 'tok');
    let auth = '';
    server.use(
      http.get('/api/ping', ({ request }) => {
        auth = request.headers.get('authorization') ?? '';
        return HttpResponse.json({ ok: true });
      }),
    );
    await expect(apiFetch<{ ok: boolean }>('/api/ping')).resolves.toEqual({ ok: true });
    expect(auth).toBe('Bearer tok');
  });

  it('maps ApiError bodies including field errors', async () => {
    server.use(
      http.post('/api/x', () =>
        HttpResponse.json(
          apiError('BAD_REQUEST', 'Invalid request', ['teamId: must not be blank', 'free text']),
          { status: 400 },
        ),
      ),
    );
    const err = (await apiFetch('/api/x', { method: 'POST', body: {} }).catch((e: unknown) => e)) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('BAD_REQUEST');
    expect(err.fieldErrors()).toEqual({ teamId: 'must not be blank' });
    expect(describeError(err)).toContain('teamId: must not be blank');
  });

  it('invokes the unauthorized handler on 401 and tolerates non-JSON bodies', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    server.use(http.get('/api/secure', () => new HttpResponse('nope', { status: 401 })));
    const err = (await apiFetch('/api/secure').catch((e: unknown) => e)) as ApiError;
    expect(err.status).toBe(401);
    expect(err.code).toBe('UNAUTHORIZED');
    expect(handler).toHaveBeenCalledOnce();
    setUnauthorizedHandler(null);
  });

  it('describes forbidden and policy-engine errors distinctly', () => {
    expect(describeError(new ApiError(403, apiError('FORBIDDEN', 'Administrator role required')))).toBe(
      'Not allowed: Administrator role required',
    );
    expect(
      describeError(new ApiError(503, apiError('POLICY_ENGINE_UNAVAILABLE', 'OPA unreachable'))),
    ).toContain('Policy engine unavailable');
  });
});
