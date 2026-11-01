import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { renderApp } from '@/test/render';
import { server } from '@/test/server';
import { auditEntries, AUDITOR_TOKEN, exceptions } from '@/test/fixtures';
import type { DeadLetter } from '@/api/types';
import { filtersFromSearch, searchFromFilters, targetLink } from './auditFilters';
import { AuditPage } from './AuditPage';

const ADMIN = '/api/v1/admin';

const deadLetter: DeadLetter = {
  id: 7,
  topic: 'response.manifest',
  partition: 0,
  offset: 42,
  key: 'req-1',
  eventId: 'evt-1',
  requestId: 'req-1',
  reason: 'poison record',
  error: 'Avro deserialization failed',
  recordedAt: '2026-10-30T10:00:00Z',
  resolvedAt: null,
};

describe('auditFilters', () => {
  it('round-trips through URL search params and drops defaults', () => {
    const f = filtersFromSearch(
      new URLSearchParams(
        'targetType=policy_exception&targetId=exc-1&limit=500&since=2026-10-01T00:00:00.000Z',
      ),
    );
    expect(f).toMatchObject({
      targetType: 'policy_exception',
      targetId: 'exc-1',
      limit: 500,
      since: '2026-10-01T00:00:00.000Z',
      actor: '',
    });
    expect(searchFromFilters(f).toString()).toBe(
      'targetType=policy_exception&targetId=exc-1&since=2026-10-01T00%3A00%3A00.000Z&limit=500',
    );
    expect(filtersFromSearch(new URLSearchParams('limit=7')).limit).toBe(200);
  });

  it('links targets to their pages', () => {
    expect(targetLink('policy_exception', 'exc-1')).toBe('/exceptions/exc-1');
    expect(targetLink('policy_configuration', 'cfg-1')).toBe('/policy-configurations/cfg-1');
    expect(targetLink('policy_bundle', 'b')).toBe('/policy-bundles');
    expect(targetLink('manifest_request', 'r')).toBeNull();
  });
});

describe('AuditPage', () => {
  it('reads filters from the URL and sends them to the API', async () => {
    const seen: string[] = [];
    server.use(
      http.get(`${ADMIN}/audit`, ({ request }) => {
        seen.push(new URL(request.url).search);
        return HttpResponse.json(auditEntries.filter((a) => a.targetType === 'policy_exception'));
      }),
    );
    renderApp(<AuditPage />, {
      route: `/audit?targetType=policy_exception&actor=admin-1&limit=500`,
      path: '/audit',
    });
    const table = await screen.findByRole('table', { name: 'Audit entries' });
    expect(within(table).getAllByRole('row').slice(1)).toHaveLength(2);
    expect(seen[0]).toBe('?targetType=policy_exception&actor=admin-1&limit=500');
    expect(screen.getByLabelText('Target type')).toHaveValue('policy_exception');
    expect(screen.getByLabelText('Actor')).toHaveValue('admin-1');
    expect(screen.getByLabelText('Limit')).toHaveValue('500');
  });

  it('renders rows with links to exception details and expandable JSON details', async () => {
    const user = userEvent.setup();
    renderApp(<AuditPage />, { route: '/audit', path: '/audit' });
    const table = await screen.findByRole('table', { name: 'Audit entries' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(3);

    const link = within(rows[0]).getByRole('link');
    expect(link).toHaveAttribute('href', `/exceptions/${exceptions[1].id}`);
    expect(within(rows[0]).getByText('EXCEPTION_CREATED')).toBeInTheDocument();

    const toggle = within(rows[0]).getByRole('button', { name: 'Show details' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);
    expect(within(rows[0]).getByRole('button', { name: 'Hide details' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    expect(within(rows[0]).getByText(/"policyId": "TOPIC_DELETE_RESTRICTED"/)).toBeInTheDocument();
  });

  it('debounces free-text filters into the URL', async () => {
    const user = userEvent.setup();
    const seen: string[] = [];
    server.use(
      http.get(`${ADMIN}/audit`, ({ request }) => {
        seen.push(new URL(request.url).search);
        return HttpResponse.json([]);
      }),
    );
    renderApp(<AuditPage />, { route: '/audit', path: '/audit' });
    await screen.findByText('No audit entries match');
    await user.type(screen.getByLabelText('Target id'), 'cfg-2');
    await waitFor(() => expect(seen.at(-1)).toBe('?targetId=cfg-2&limit=200'));
    expect(seen.filter((s) => s.includes('targetId')).length).toBeLessThanOrEqual(2);
  });

  it('shows an empty dead-letters state by default', async () => {
    renderApp(<AuditPage />, { route: '/audit', path: '/audit' });
    expect(await screen.findByText('No open dead letters')).toBeInTheDocument();
  });

  it('lets admins resolve a dead letter, and hides the button from auditors', async () => {
    const user = userEvent.setup();
    const resolved: number[] = [];
    server.use(
      http.get(`${ADMIN}/dead-letters`, () => HttpResponse.json([deadLetter])),
      http.post(`${ADMIN}/dead-letters/:id/resolve`, ({ params }) => {
        resolved.push(Number(params.id));
        return HttpResponse.json({ id: Number(params.id), resolved: true });
      }),
    );
    const first = renderApp(<AuditPage />, { route: '/audit', path: '/audit' });
    const table = await screen.findByRole('table', { name: 'Dead letters' });
    expect(within(table).getByText('poison record')).toBeInTheDocument();
    await user.click(within(table).getByRole('button', { name: 'Resolve' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Resolve' }));
    await waitFor(() => expect(resolved).toEqual([7]));
    first.unmount();

    renderApp(<AuditPage />, { token: AUDITOR_TOKEN, route: '/audit', path: '/audit' });
    const table2 = await screen.findByRole('table', { name: 'Dead letters' });
    expect(within(table2).queryByRole('button', { name: 'Resolve' })).not.toBeInTheDocument();
  });
});
