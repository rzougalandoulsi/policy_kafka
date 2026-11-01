import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderApp } from '@/test/render';
import { shadowFindings } from '@/test/fixtures';
import { ShadowFindingsPage, findingsByPolicy, withParams } from './ShadowFindingsPage';

describe('ShadowFindingsPage', () => {
  it('summarises findings per policy', () => {
    expect(findingsByPolicy([...shadowFindings, { ...shadowFindings[0], id: 9, requestId: 'req-other' }])).toEqual([
      { policyId: 'AUTHORIZED_PREFIX_REQUIRED', count: 2, requests: 2 },
      { policyId: 'PROD_TOPIC_DELETE_REQUIRES_APPROVAL', count: 1, requests: 1 },
    ]);
  });

  it('applies filter patches to the search params and clears empty ones', () => {
    const next = withParams(new URLSearchParams('policyId=A&teamId=team-a'), { teamId: '', environment: 'prod' });
    expect(next.toString()).toBe('policyId=A&environment=prod');
  });

  it('lists findings with what the policy would have done', async () => {
    renderApp(<ShadowFindingsPage />, { route: '/shadow-findings', path: '/shadow-findings' });
    const table = await screen.findByRole('table', { name: 'Shadow findings' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText('would deny')).toBeInTheDocument();
    expect(within(rows[1]).getByText('would need approval')).toBeInTheDocument();
    expect(within(rows[0]).getByRole('link', { name: 'team-a' })).toHaveAttribute('href', '/teams/team-a');
  });

  it('filters by policy through the URL', async () => {
    const user = userEvent.setup();
    renderApp(<ShadowFindingsPage />, { route: '/shadow-findings', path: '/shadow-findings' });
    await screen.findByRole('table', { name: 'Shadow findings' });
    await user.selectOptions(screen.getByLabelText('Policy'), 'PROD_TOPIC_DELETE_REQUIRES_APPROVAL');
    await screen.findByText('1 shown, newest first (limit 500).');
    const table = screen.getByRole('table', { name: 'Shadow findings' });
    expect(within(table).getAllByRole('row').slice(1)).toHaveLength(1);
  });
});
