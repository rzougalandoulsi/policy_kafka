import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { renderApp } from '@/test/render';
import { server } from '@/test/server';
import { AUDITOR_TOKEN, teamUsage, teams } from '@/test/fixtures';
import { apiError } from '@/test/handlers';
import { defaultsFrom, describeTeam, parseEnvironments, teamSchema, toBody } from './teamSchema';
import { TeamsPage, quotaSummary } from './TeamsPage';
import { TeamDetailPage, capacityRows } from './TeamDetailPage';

const ADMIN = '/api/v1/admin';

describe('teamSchema', () => {
  it('turns the form into the PUT body: lower-cased prefixes, only set quotas, parsed environments', () => {
    const form = teamSchema.parse({
      ...defaultsFrom(undefined, 'team-x'),
      authorizedPrefixes: ['Team-X.', 'shared.'],
      quotas: { topics: '10', topicPartitions: '', identityPools: 0 },
      allowedRoles: ['DeveloperRead'],
      environmentsJson: '{"production": {"changeWindow": "weekdays"}}',
      comment: 'first',
    });
    expect(toBody(form)).toEqual({
      authorizedPrefixes: ['team-x.', 'shared.'],
      quotas: { topics: 10, identityPools: 0 },
      allowedRoles: ['DeveloperRead'],
      environments: { production: { changeWindow: 'weekdays' } },
      source: 'governance',
      sourceVersion: null,
      comment: 'first',
    });
  });

  it.each([
    ['a prefix with whitespace', { authorizedPrefixes: ['team x.'] }],
    ['a negative quota', { quotas: { topics: '-1' } }],
    ['a fractional quota', { quotas: { topics: '1.5' } }],
    ['an external source without a version', { source: 'external', sourceVersion: '' }],
    ['environments that are not an object', { environmentsJson: '[1]' }],
    ['an invalid team id', { teamId: 'team/x' }],
  ])('rejects %s', (_label, patch) => {
    expect(teamSchema.safeParse({ ...defaultsFrom(undefined, 'team-x'), ...patch }).success).toBe(false);
  });

  it('parses environments leniently', () => {
    expect(parseEnvironments('')).toEqual({});
    expect(parseEnvironments('{"a": {"b": 1}}')).toEqual({ a: { b: 1 } });
    expect(parseEnvironments('{"a": 1}')).toBeNull();
    expect(parseEnvironments('nope')).toBeNull();
  });

  it('describes a configuration in plain words', () => {
    expect(describeTeam({ authorizedPrefixes: ['team-a.', 'orders.'], quotas: { topics: 25 }, allowedRoles: [] })).toBe(
      'Topics must start with "team-a." or "orders.". Quotas: topics ≤ 25 across all repositories per environment. Roles follow the global rbac configuration.',
    );
    expect(describeTeam({ authorizedPrefixes: [], quotas: {}, allowedRoles: ['DeveloperRead'] })).toMatch(/every topic is denied/);
    expect(quotaSummary(teams[0])).toBe('topics ≤ 25, topicPartitions ≤ 200');
  });

  it('computes headroom per quota key and environment', () => {
    const rows = capacityRows(teamUsage, { topics: 25, topicPartitions: 200 }, 'production');
    expect(rows.find((r) => r.key === 'topics')).toEqual({ key: 'topics', current: 1, reserved: 2, quota: 25, headroom: 22 });
    expect(rows.find((r) => r.key === 'topicPartitions')).toEqual({ key: 'topicPartitions', current: 6, reserved: 128, quota: 200, headroom: 66 });
    expect(rows.find((r) => r.key === 'identityPools')?.headroom).toBeUndefined();
  });
});

describe('TeamsPage', () => {
  it('lists stored teams with prefixes, quotas and source', async () => {
    renderApp(<TeamsPage />, { route: '/teams', path: '/teams' });
    const table = await screen.findByRole('table', { name: 'Teams' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByRole('link', { name: 'team-a' })).toHaveAttribute('href', '/teams/team-a');
    expect(within(rows[0]).getByText('orders.')).toBeInTheDocument();
    expect(within(rows[0]).getByText('topics ≤ 25, topicPartitions ≤ 200')).toBeInTheDocument();
    expect(within(rows[1]).getByText('External registry')).toBeInTheDocument();
  });

  it('hides the create action from auditors', async () => {
    renderApp(<TeamsPage />, { route: '/teams', path: '/teams', token: AUDITOR_TOKEN });
    await screen.findByRole('table', { name: 'Teams' });
    expect(screen.queryByRole('button', { name: /new team/i })).not.toBeInTheDocument();
  });

  it('creates a team with the expected PUT body', async () => {
    const user = userEvent.setup();
    let putTeam: string | null = null;
    let putBody: unknown = null;
    server.use(
      http.put(`${ADMIN}/teams/:teamId`, async ({ params, request }) => {
        putTeam = String(params.teamId);
        putBody = await request.json();
        return HttpResponse.json({ ...teams[0], teamId: putTeam, version: 1, ...(putBody as object) });
      }),
    );
    renderApp(<TeamsPage />, { route: '/teams', path: '/teams' });
    await user.click(await screen.findByRole('button', { name: /new team/i }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^Team id/), 'team-new');
    await user.type(within(dialog).getByLabelText(/Authorised topic prefixes/), 'team-new.{Enter}billing.{Enter}');
    await user.type(within(dialog).getByLabelText('topics'), '12');
    await user.type(within(dialog).getByLabelText(/^Comment/), 'onboarding');
    expect(within(dialog).getByText(/Topics must start with "team-new." or "billing."/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(putTeam).toBe('team-new'));
    expect(putBody).toEqual({
      authorizedPrefixes: ['team-new.', 'billing.'],
      quotas: { topics: 12 },
      allowedRoles: [],
      environments: {},
      source: 'governance',
      sourceVersion: null,
      comment: 'onboarding',
    });
    expect(await screen.findByTestId('elsewhere')).toBeInTheDocument(); // navigated to the new team
  });

  it('shows the API error when the team is externally managed', async () => {
    const user = userEvent.setup();
    server.use(
      http.put(`${ADMIN}/teams/:teamId`, () =>
        HttpResponse.json(apiError('TEAM_CONFIGURATION_EXTERNAL', 'managed by an external registry'), { status: 409 }),
      ),
    );
    renderApp(<TeamsPage />, { route: '/teams', path: '/teams' });
    await user.click(await screen.findByRole('button', { name: /new team/i }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^Team id/), 'team-ext');
    await user.type(within(dialog).getByLabelText(/Authorised topic prefixes/), 'ext.{Enter}');
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(await screen.findByText(/managed by an external registry/)).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});

describe('TeamDetailPage', () => {
  it('shows configuration, capacity with headroom, repositories and reservations', async () => {
    renderApp(<TeamDetailPage />, { route: '/teams/team-a', path: '/teams/:teamId' });
    expect(await screen.findByRole('heading', { name: /team-a/ })).toBeInTheDocument();
    expect(screen.getByText('topics ≤ 25')).toBeInTheDocument();
    const production = await screen.findByRole('table', { name: 'Capacity in production' });
    const partitions = within(production).getAllByRole('row').find((r) => within(r).queryByText('topicPartitions'));
    expect(partitions).toBeDefined();
    expect(within(partitions!).getByText('128')).toBeInTheDocument(); // reserved
    expect(within(partitions!).getByText('66')).toBeInTheDocument(); // headroom
    const repos = screen.getByRole('table', { name: 'Repositories' });
    expect(within(repos).getAllByRole('row').slice(1)).toHaveLength(3);
    expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reset to defaults' })).toBeInTheDocument();
  });

  it('explains defaults for a team without stored configuration and offers to configure it', async () => {
    renderApp(<TeamDetailPage />, { route: '/teams/team-z', path: '/teams/:teamId' });
    expect(await screen.findByText('Defaults')).toBeInTheDocument();
    expect(screen.getByText('team-z.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Configure' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reset to defaults' })).not.toBeInTheDocument();
  });

  it('edits a team and sends every field, prefilled from the stored version', async () => {
    const user = userEvent.setup();
    let putBody: Record<string, unknown> | null = null;
    server.use(
      http.put(`${ADMIN}/teams/:teamId`, async ({ request }) => {
        putBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...teams[0], ...putBody, version: 4 });
      }),
    );
    renderApp(<TeamDetailPage />, { route: '/teams/team-a', path: '/teams/:teamId' });
    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByLabelText(/^Team id/)).toBeDisabled();
    expect(within(dialog).getByText('orders.')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('topics')).toHaveValue(25);
    await user.clear(within(dialog).getByLabelText('topics'));
    await user.type(within(dialog).getByLabelText('topics'), '30');
    await user.click(within(dialog).getByRole('button', { name: 'Save new version' }));
    await waitFor(() => expect(putBody).not.toBeNull());
    expect(putBody).toMatchObject({
      authorizedPrefixes: ['team-a.', 'orders.'],
      quotas: { topics: 30, topicPartitions: 200 },
      environments: { production: { changeWindow: 'weekdays' } },
      source: 'governance',
    });
  });

  it('warns about external ownership and hides write controls from auditors', async () => {
    renderApp(<TeamDetailPage />, { route: '/teams/team-ext', path: '/teams/:teamId', token: AUDITOR_TOKEN });
    expect(await screen.findByText(/Owned by an external registry/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
  });
});
