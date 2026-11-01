import { http, HttpResponse } from 'msw';
import * as fx from './fixtures';

const ADMIN = '/api/v1/admin';

/** Default happy-path handlers; tests override per case with server.use(...). */
export const handlers = [
  http.get(`${ADMIN}/exceptions`, ({ request }) => {
    const url = new URL(request.url);
    const teamId = url.searchParams.get('teamId');
    const includeInactive = url.searchParams.get('includeInactive') === 'true';
    const now = fx.NOW.getTime();
    return HttpResponse.json(
      fx.exceptions.filter(
        (e) =>
          (!teamId || e.teamId === teamId) &&
          (includeInactive || (e.revokedAt == null && new Date(e.expiresAt).getTime() > now)),
      ),
    );
  }),
  http.get(`${ADMIN}/exceptions/:id`, ({ params }) => {
    const e = fx.exceptions.find((x) => x.id === params.id);
    return e
      ? HttpResponse.json(e)
      : HttpResponse.json(notFound(`Exception ${params.id} not found`), { status: 404 });
  }),
  http.post(`${ADMIN}/exceptions`, async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    return HttpResponse.json({
      ...fx.exceptions[0],
      ...body,
      id: 'exc-new',
      environment: body.environment || '*',
      resourcePattern: body.resourcePattern || '*',
      actionTypes: body.actionTypes ?? [],
      createdAt: fx.NOW.toISOString(),
      revokedAt: null,
      revokedBy: null,
      revokeReason: null,
    });
  }),
  http.delete(`${ADMIN}/exceptions/:id`, ({ params, request }) => {
    const e = fx.exceptions.find((x) => x.id === params.id);
    if (!e) return HttpResponse.json(notFound('nope'), { status: 404 });
    const reason = new URL(request.url).searchParams.get('reason');
    return HttpResponse.json({
      ...e,
      revokedAt: fx.NOW.toISOString(),
      revokedBy: 'admin-1',
      revokeReason: reason,
    });
  }),

  http.get(`${ADMIN}/policy-configurations`, () => HttpResponse.json(fx.policyConfigs)),
  http.get(`${ADMIN}/policy-configurations/:id`, ({ params }) => {
    const c = fx.policyConfigs.find((x) => x.id === params.id);
    return c ? HttpResponse.json(c) : HttpResponse.json(notFound('missing'), { status: 404 });
  }),
  http.post(`${ADMIN}/policy-configurations`, async ({ request }) => {
    const body = (await request.json()) as { content: unknown; comment?: string };
    return HttpResponse.json({
      ...fx.policyConfigs[2],
      id: 'cfg-new',
      version: 4,
      content: body.content,
      comment: body.comment ?? null,
    });
  }),
  http.post(`${ADMIN}/policy-configurations/rollback`, () =>
    HttpResponse.json({ ...fx.policyConfigs[0], status: 'ACTIVE' }),
  ),
  http.post(`${ADMIN}/policy-configurations/:id/activate`, ({ params }) => {
    const c = fx.policyConfigs.find((x) => x.id === params.id);
    return HttpResponse.json({ ...c, status: 'ACTIVE' });
  }),

  http.get(`${ADMIN}/policy-bundles`, () => HttpResponse.json(fx.bundles)),
  http.get(`${ADMIN}/policy-bundles/diagnostics`, () => HttpResponse.json(fx.diagnostics)),
  http.get(`${ADMIN}/policy-bundles/:id`, ({ params }) =>
    HttpResponse.json(fx.bundles.find((x) => x.id === params.id)),
  ),
  http.post(`${ADMIN}/policy-bundles`, () =>
    HttpResponse.json({ ...fx.bundles[1], id: 'bundle-new', revision: 'v4', status: 'UPLOADED' }),
  ),
  http.post(`${ADMIN}/policy-bundles/rollback`, ({ request }) => {
    const bundle = new URL(request.url).searchParams.get('bundle');
    const retired = fx.bundles.find((x) => x.bundleName === bundle && x.status === 'RETIRED');
    return retired
      ? HttpResponse.json({ ...retired, status: 'ACTIVE' })
      : HttpResponse.json(apiError('BAD_REQUEST', `No retired version of bundle ${bundle}`), { status: 400 });
  }),
  http.get(`${ADMIN}/policy-releases/current`, () => HttpResponse.json(fx.diagnostics)),
  http.post(`${ADMIN}/policy-releases`, async ({ request }) => {
    const body = (await request.json()) as { versionIds: string[] };
    const chosen = body.versionIds.map((id) => fx.bundles.find((x) => x.id === id)).filter((x) => !!x);
    if (chosen.length !== 6)
      return HttpResponse.json(apiError('BAD_REQUEST', 'A release needs one version per bundle'), {
        status: 400,
      });
    return HttpResponse.json({
      id: 'rel-new',
      revisions: Object.fromEntries(chosen.map((v) => [v.bundleName, v.revision])),
    });
  }),
  http.post(`${ADMIN}/policy-bundles/:id/activate`, ({ params }) =>
    HttpResponse.json({ ...fx.bundles.find((x) => x.id === params.id), status: 'ACTIVE' }),
  ),

  http.get(`${ADMIN}/approval-configurations`, () => HttpResponse.json(fx.approvalConfigs)),
  http.post(`${ADMIN}/approval-configurations`, async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    return HttpResponse.json({
      ...fx.approvalConfigs[0],
      ...body,
      id: 'apr-new',
      version: 2,
      status: 'DRAFT',
      activatedAt: null,
    });
  }),
  http.post(`${ADMIN}/approval-configurations/:id/activate`, () =>
    HttpResponse.json({ ...fx.approvalConfigs[0], id: 'apr-new', status: 'ACTIVE' }),
  ),

  http.get(`${ADMIN}/audit`, ({ request }) => {
    const url = new URL(request.url);
    const targetType = url.searchParams.get('targetType');
    const targetId = url.searchParams.get('targetId');
    const actor = url.searchParams.get('actor');
    return HttpResponse.json(
      fx.auditEntries.filter(
        (a) =>
          (!targetType || a.targetType === targetType) &&
          (!targetId || a.targetId === targetId) &&
          (!actor || a.actorId === actor),
      ),
    );
  }),
  http.get(`${ADMIN}/dead-letters`, () => HttpResponse.json(fx.deadLetters)),
  http.post(`${ADMIN}/dead-letters/:id/resolve`, ({ params }) =>
    HttpResponse.json({ id: Number(params.id), resolved: true }),
  ),
  http.get(`${ADMIN}/policies`, () => HttpResponse.json(fx.catalog)),

  http.get(`${ADMIN}/teams`, () => HttpResponse.json(fx.teams)),
  http.get(`${ADMIN}/teams/:teamId/history`, ({ params }) =>
    HttpResponse.json(params.teamId === 'team-a' ? fx.teamHistory : []),
  ),
  http.get(`${ADMIN}/teams/:teamId/usage`, ({ params }) =>
    HttpResponse.json(
      params.teamId === 'team-a'
        ? fx.teamUsage
        : { teamId: params.teamId, currentByEnvironment: {}, reservedByEnvironment: {}, repositories: [], reservations: [] },
    ),
  ),
  http.get(`${ADMIN}/teams/:teamId`, ({ params }) => {
    const t = fx.teams.find((x) => x.teamId === params.teamId);
    return HttpResponse.json(t ?? fx.teamDefaults(String(params.teamId)));
  }),
  http.put(`${ADMIN}/teams/:teamId`, async ({ params, request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    const existing = fx.teams.find((x) => x.teamId === params.teamId);
    if (existing?.source === 'external' && body.source === 'external')
      return HttpResponse.json(apiError('TEAM_CONFIGURATION_EXTERNAL', 'managed by an external registry'), { status: 409 });
    return HttpResponse.json({
      ...(existing ?? fx.teamDefaults(String(params.teamId))),
      ...body,
      version: (existing?.version ?? 0) + 1,
      updatedBy: 'admin-1',
      updatedAt: fx.NOW.toISOString(),
    });
  }),
  http.delete(`${ADMIN}/teams/:teamId`, ({ params }) => HttpResponse.json(fx.teamDefaults(String(params.teamId)))),

  http.get(`${ADMIN}/shadow-findings`, ({ request }) => {
    const url = new URL(request.url);
    const policyId = url.searchParams.get('policyId');
    const teamId = url.searchParams.get('teamId');
    return HttpResponse.json(
      fx.shadowFindings.filter((f) => (!policyId || f.policyId === policyId) && (!teamId || f.teamId === teamId)),
    );
  }),
];

export function apiError(
  code: string,
  message: string,
  errors: string[] = [],
  details: Record<string, unknown> = {},
) {
  return { code, message, timestamp: fx.NOW.toISOString(), errors, details };
}
export const notFound = (m: string) => apiError('NOT_FOUND', m);
export const forbidden = (m = 'Administrator role required') => apiError('FORBIDDEN', m);
