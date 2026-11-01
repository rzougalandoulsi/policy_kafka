import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { renderApp } from '@/test/render';
import { server } from '@/test/server';
import { approvalConfigs, AUDITOR_TOKEN } from '@/test/fixtures';
import { approvalRuleSchema, describeRule } from './approvalRuleSchema';
import { ApprovalRulesPage } from './ApprovalRulesPage';

const ADMIN = '/api/v1/admin';

describe('approvalRuleSchema', () => {
  const base = {
    approverRoles: ['governance-approver'],
    requiredApprovals: 1,
    separationOfDuties: true,
    ttlHours: 72,
    activateImmediately: false,
  };

  it('accepts the seeded rule and coerces numeric strings', () => {
    expect(approvalRuleSchema.parse({ ...base, requiredApprovals: '3', ttlHours: '24' })).toMatchObject({
      requiredApprovals: 3,
      ttlHours: 24,
    });
  });

  it.each([
    ['empty roles', { approverRoles: [] }],
    ['0 approvals', { requiredApprovals: 0 }],
    ['11 approvals', { requiredApprovals: 11 }],
    ['fractional approvals', { requiredApprovals: 1.5 }],
    ['0 hours', { ttlHours: 0 }],
    ['721 hours', { ttlHours: 721 }],
  ])('rejects %s', (_label, patch) => {
    expect(approvalRuleSchema.safeParse({ ...base, ...patch }).success).toBe(false);
  });

  it('describes a rule in plain words', () => {
    expect(
      describeRule({
        approverRoles: ['a', 'b'],
        requiredApprovals: 2,
        separationOfDuties: true,
        ttlHours: 48,
      }),
    ).toBe(
      '2 approvals from any of the roles a, b within 48 hours. The requester can never approve their own request.',
    );
    expect(
      describeRule({ approverRoles: ['a'], requiredApprovals: 1, separationOfDuties: false, ttlHours: 1 }),
    ).toBe('1 approval from any of the roles a within 1 hour.');
  });
});

describe('ApprovalRulesPage', () => {
  const twoVersions = [
    approvalConfigs[0],
    {
      ...approvalConfigs[0],
      id: 'apr-2',
      version: 2,
      requiredApprovals: 2,
      status: 'DRAFT' as const,
      activatedAt: null,
      createdBy: 'admin-1',
    },
  ];

  it('renders the active rule card and the version history', async () => {
    server.use(http.get(`${ADMIN}/approval-configurations`, () => HttpResponse.json(twoVersions)));
    renderApp(<ApprovalRulesPage />, { route: '/approval-rules', path: '/approval-rules' });

    expect(await screen.findByRole('heading', { name: 'Active rule' })).toBeInTheDocument();
    expect(screen.getByText('Yes, requesters cannot approve their own request')).toBeInTheDocument();
    expect(screen.getByText('72 hours')).toBeInTheDocument();

    const table = screen.getByRole('table', { name: 'Approval rule versions' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText('v2')).toBeInTheDocument(); // newest first
    expect(within(rows[0]).getByRole('button', { name: 'Activate' })).toBeInTheDocument();
    expect(within(rows[1]).queryByRole('button', { name: 'Activate' })).not.toBeInTheDocument();
  });

  it('hides every write control from auditors', async () => {
    server.use(http.get(`${ADMIN}/approval-configurations`, () => HttpResponse.json(twoVersions)));
    renderApp(<ApprovalRulesPage />, {
      token: AUDITOR_TOKEN,
      route: '/approval-rules',
      path: '/approval-rules',
    });
    expect(await screen.findByRole('heading', { name: 'Active rule' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New version' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Activate' })).not.toBeInTheDocument();
  });

  it('activates an older version after confirmation', async () => {
    const user = userEvent.setup();
    const activated: string[] = [];
    server.use(
      http.get(`${ADMIN}/approval-configurations`, () => HttpResponse.json(twoVersions)),
      http.post(`${ADMIN}/approval-configurations/:id/activate`, ({ params }) => {
        activated.push(String(params.id));
        return HttpResponse.json({ ...twoVersions[1], status: 'ACTIVE' });
      }),
    );
    renderApp(<ApprovalRulesPage />, { route: '/approval-rules', path: '/approval-rules' });
    await user.click(await screen.findByRole('button', { name: 'Activate' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/There is no separate rollback/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Activate' }));
    await waitFor(() => expect(activated).toEqual(['apr-2']));
  });

  it('creates a new version with the expected body, prefilled from the active rule', async () => {
    const user = userEvent.setup();
    let posted: unknown = null;
    const activated: string[] = [];
    server.use(
      http.post(`${ADMIN}/approval-configurations`, async ({ request }) => {
        posted = await request.json();
        return HttpResponse.json({
          ...approvalConfigs[0],
          ...(posted as object),
          id: 'apr-new',
          version: 2,
          status: 'DRAFT',
        });
      }),
      http.post(`${ADMIN}/approval-configurations/:id/activate`, ({ params }) => {
        activated.push(String(params.id));
        return HttpResponse.json({ ...approvalConfigs[0], id: 'apr-new', status: 'ACTIVE' });
      }),
    );
    renderApp(<ApprovalRulesPage />, { route: '/approval-rules', path: '/approval-rules' });
    await user.click(await screen.findByRole('button', { name: 'New version' }));
    const dialog = await screen.findByRole('dialog');

    // prefilled from the active rule
    expect(within(dialog).getByText('governance-approver')).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Required approvals/)).toHaveValue(1);
    expect(within(dialog).getByLabelText(/Time to decide/)).toHaveValue(72);

    await user.type(within(dialog).getByLabelText(/Approver roles/), 'security-lead{Enter}');
    await user.clear(within(dialog).getByLabelText(/Required approvals/));
    await user.type(within(dialog).getByLabelText(/Required approvals/), '2');
    await user.click(within(dialog).getByRole('button', { name: '168h' }));
    await user.click(within(dialog).getByRole('switch', { name: 'Separation of duties' }));
    expect(
      within(dialog).getByText(
        /2 approvals from any of the roles governance-approver, security-lead within 168 hours\./,
      ),
    ).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Create version' }));
    await waitFor(() =>
      expect(posted).toEqual({
        approverRoles: ['governance-approver', 'security-lead'],
        requiredApprovals: 2,
        separationOfDuties: false,
        ttlHours: 168,
      }),
    );
    expect(activated).toEqual([]);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('chains activation when "Activate immediately" is checked', async () => {
    const user = userEvent.setup();
    const activated: string[] = [];
    server.use(
      http.post(`${ADMIN}/approval-configurations/:id/activate`, ({ params }) => {
        activated.push(String(params.id));
        return HttpResponse.json({ ...approvalConfigs[0], id: 'apr-new', status: 'ACTIVE' });
      }),
    );
    renderApp(<ApprovalRulesPage />, { route: '/approval-rules', path: '/approval-rules' });
    await user.click(await screen.findByRole('button', { name: 'New version' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('checkbox', { name: /Activate immediately/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Create and activate' }));
    await waitFor(() => expect(activated).toEqual(['apr-new']));
  });

  it('shows a validation error instead of posting when quorum is out of range', async () => {
    const user = userEvent.setup();
    let posted = false;
    server.use(
      http.post(`${ADMIN}/approval-configurations`, () => {
        posted = true;
        return HttpResponse.json(approvalConfigs[0]);
      }),
    );
    renderApp(<ApprovalRulesPage />, { route: '/approval-rules', path: '/approval-rules' });
    await user.click(await screen.findByRole('button', { name: 'New version' }));
    const dialog = await screen.findByRole('dialog');
    await user.clear(within(dialog).getByLabelText(/Required approvals/));
    await user.type(within(dialog).getByLabelText(/Required approvals/), '11');
    await user.click(within(dialog).getByRole('button', { name: 'Create version' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('At most 10 approvals');
    expect(posted).toBe(false);
  });
});
