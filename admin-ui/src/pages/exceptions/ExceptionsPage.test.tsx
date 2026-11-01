import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { renderApp } from '@/test/render';
import { server } from '@/test/server';
import { AUDITOR_TOKEN, exceptions } from '@/test/fixtures';
import { apiError } from '@/test/handlers';
import { ExceptionsPage } from './ExceptionsPage';

describe('ExceptionsPage', () => {
  it('lists active exceptions with scope and lifecycle badges', async () => {
    renderApp(<ExceptionsPage />, { route: '/exceptions', path: '/exceptions' });
    expect(await screen.findByRole('link', { name: 'MAX_PARTITIONS_PER_TOPIC' })).toHaveAttribute(
      'href',
      `/exceptions/${exceptions[0].id}`,
    );
    expect(screen.getByRole('link', { name: 'TOPIC_DELETE_RESTRICTED' })).toBeInTheDocument();
    // revoked one is filtered out by the API unless includeInactive
    expect(screen.queryByRole('link', { name: 'TEAM_PREFIX_REQUIRED' })).not.toBeInTheDocument();
    const table = screen.getByRole('table');
    expect(within(table).getByText('any principal', { exact: false })).toBeInTheDocument();
    expect(within(table).getByText('Expiring soon')).toBeInTheDocument();
    expect(within(table).getByText('Active')).toBeInTheDocument();
  });

  it('filters by lifecycle status client-side', async () => {
    const user = userEvent.setup();
    renderApp(<ExceptionsPage />, { route: '/exceptions', path: '/exceptions' });
    await screen.findByRole('link', { name: 'MAX_PARTITIONS_PER_TOPIC' });
    await user.selectOptions(screen.getByLabelText('Status'), 'expiring');
    expect(screen.queryByRole('link', { name: 'MAX_PARTITIONS_PER_TOPIC' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'TOPIC_DELETE_RESTRICTED' })).toBeInTheDocument();
  });

  it('includes revoked entries when the toggle is on', async () => {
    const user = userEvent.setup();
    renderApp(<ExceptionsPage />, { route: '/exceptions', path: '/exceptions' });
    await screen.findByRole('link', { name: 'MAX_PARTITIONS_PER_TOPIC' });
    await user.click(screen.getByRole('switch', { name: 'Include expired and revoked' }));
    expect(await screen.findByRole('link', { name: 'TEAM_PREFIX_REQUIRED' })).toBeInTheDocument();
    expect(within(screen.getByRole('table')).getByText('Revoked')).toBeInTheDocument();
  });

  it('shows an error state when the API fails', async () => {
    server.use(
      http.get('/api/v1/admin/exceptions', () =>
        HttpResponse.json(apiError('INTERNAL_ERROR', 'boom'), { status: 500 }),
      ),
    );
    renderApp(<ExceptionsPage />, { route: '/exceptions', path: '/exceptions' });
    expect(await screen.findByRole('alert')).toHaveTextContent('boom');
  });

  it('hides the create action from auditors', async () => {
    renderApp(<ExceptionsPage />, { route: '/exceptions', path: '/exceptions', token: AUDITOR_TOKEN });
    await screen.findByRole('link', { name: 'MAX_PARTITIONS_PER_TOPIC' });
    expect(screen.queryByRole('button', { name: /new exception/i })).not.toBeInTheDocument();
  });

  it('opens the drawer, disables non-bypassable policies and posts a valid payload', async () => {
    const user = userEvent.setup();
    let captured: Record<string, unknown> | null = null;
    server.use(
      http.post('/api/v1/admin/exceptions', async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...exceptions[0], ...captured, id: 'exc-created' });
      }),
    );
    renderApp(<ExceptionsPage />, { route: '/exceptions', path: '/exceptions' });
    await screen.findByRole('link', { name: 'MAX_PARTITIONS_PER_TOPIC' });
    await user.click(screen.getByRole('button', { name: /new exception/i }));

    const dialog = await screen.findByRole('dialog');
    const policy = await within(dialog).findByLabelText(/^Policy/);
    const hardDeny = within(policy).getByRole('option', {
      name: /RBAC_ADMIN_ROLE_FORBIDDEN/,
    }) as HTMLOptionElement;
    expect(hardDeny.disabled).toBe(true);
    expect(hardDeny.textContent).toContain('not bypassable');
    const approval = within(policy).getByRole('option', {
      name: /PROD_TOPIC_DELETE_REQUIRES_APPROVAL/,
    }) as HTMLOptionElement;
    expect(approval.disabled).toBe(true);

    await user.selectOptions(policy, 'MAX_PARTITIONS_PER_TOPIC');
    await user.type(within(dialog).getByLabelText(/^Team/), 'team-a');
    await user.type(within(dialog).getByLabelText(/^Resource pattern/), 'team-a.*');
    await user.type(within(dialog).getByLabelText('Try a resource name'), 'team-a.orders');
    expect(within(dialog).getByText('Match')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: '7d' }));
    await user.type(within(dialog).getByLabelText(/^Reason/), 'load test');
    await user.click(within(dialog).getByRole('button', { name: 'Create exception' }));

    expect(await screen.findByTestId('elsewhere')).toBeInTheDocument(); // navigated to /exceptions/exc-created
    expect(captured).toMatchObject({
      policyId: 'MAX_PARTITIONS_PER_TOPIC',
      teamId: 'team-a',
      resourcePattern: 'team-a.*',
      actionTypes: [],
      reason: 'load test',
    });
    expect(captured).not.toHaveProperty('environment');
    expect(captured).not.toHaveProperty('principalId');
    expect(new Date(captured!.expiresAt as string).getTime()).toBeGreaterThan(Date.now());
  });

  it('shows validation errors before submitting', async () => {
    const user = userEvent.setup();
    renderApp(<ExceptionsPage />, { route: '/exceptions', path: '/exceptions' });
    await screen.findByRole('link', { name: 'MAX_PARTITIONS_PER_TOPIC' });
    await user.click(screen.getByRole('button', { name: /new exception/i }));
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByRole('option', { name: /MAX_PARTITIONS_PER_TOPIC/ });
    await user.click(within(dialog).getByRole('button', { name: 'Create exception' }));
    const alerts = await within(dialog).findAllByRole('alert');
    const text = alerts.map((a) => a.textContent).join(' ');
    expect(text).toContain('Policy is required');
    expect(text).toContain('Team is required');
    expect(text).toContain('Expiry is required');
    expect(text).toContain('Reason is required');
  });

  it('falls back to a free-text policy id when the catalogue is unavailable', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/v1/admin/policies', () =>
        HttpResponse.json(apiError('POLICY_ENGINE_UNAVAILABLE', 'OPA down'), { status: 503 }),
      ),
    );
    renderApp(<ExceptionsPage />, { route: '/exceptions', path: '/exceptions' });
    await screen.findByRole('link', { name: 'MAX_PARTITIONS_PER_TOPIC' });
    await user.click(screen.getByRole('button', { name: /new exception/i }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText(/catalogue is unavailable/)).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/^Policy/)).toHaveAttribute(
      'placeholder',
      'MAX_PARTITIONS_PER_TOPIC',
    );
  });

  it('maps server field errors onto the form', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('/api/v1/admin/exceptions', () =>
        HttpResponse.json(apiError('BAD_REQUEST', 'Invalid request', ['teamId: unknown team']), {
          status: 400,
        }),
      ),
    );
    renderApp(<ExceptionsPage />, { route: '/exceptions', path: '/exceptions' });
    await screen.findByRole('link', { name: 'MAX_PARTITIONS_PER_TOPIC' });
    await user.click(screen.getByRole('button', { name: /new exception/i }));
    const dialog = await screen.findByRole('dialog');
    await user.selectOptions(await within(dialog).findByLabelText(/^Policy/), 'MAX_PARTITIONS_PER_TOPIC');
    await user.type(within(dialog).getByLabelText(/^Team/), 'team-zzz');
    await user.click(within(dialog).getByRole('button', { name: '24h' }));
    await user.type(within(dialog).getByLabelText(/^Reason/), 'x');
    await user.click(within(dialog).getByRole('button', { name: 'Create exception' }));
    expect(await within(dialog).findByText('unknown team')).toBeInTheDocument();
  });
});
