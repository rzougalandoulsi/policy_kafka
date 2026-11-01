import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { renderApp } from '@/test/render';
import { ADMIN_TOKEN, exceptions, NOW } from '@/test/fixtures';
import { exceptionCounts, OverviewPage } from './OverviewPage';

describe('OverviewPage', () => {
  it('renders the six health cards from the API', async () => {
    renderApp(<OverviewPage />, { token: ADMIN_TOKEN });
    const config = screen.getByTestId('card-policy-configuration');
    expect(await within(config).findByText('v2')).toBeInTheDocument();
    expect(within(config).getByText('Raise partitions per topic')).toBeInTheDocument();

    const bundle = screen.getByTestId('card-policy-bundle');
    expect(await within(bundle).findByText('in sync')).toBeInTheDocument();

    const approval = screen.getByTestId('card-approval-rule');
    expect(await within(approval).findByText('governance-approver')).toBeInTheDocument();
    expect(within(approval).getByText('1 approval')).toBeInTheDocument();

    const exc = screen.getByTestId('card-scoped-exceptions');
    expect(await within(exc).findByText('Active')).toBeInTheDocument();

    const dl = screen.getByTestId('card-dead-letters');
    expect(await within(dl).findByText('0')).toBeInTheDocument();

    const audit = screen.getByTestId('card-recent-audit');
    expect(await within(audit).findByText('POLICY_CONFIGURATION_ACTIVATED')).toBeInTheDocument();
    expect(within(audit).getAllByText('EXCEPTION_CREATED')).toHaveLength(2);
  });

  it('keeps the other cards when one endpoint fails', async () => {
    server.use(
      http.get('/api/v1/admin/dead-letters', () =>
        HttpResponse.json(
          { code: 'INTERNAL_ERROR', message: 'Unexpected error', errors: [], details: {} },
          { status: 503 },
        ),
      ),
    );
    renderApp(<OverviewPage />, { token: ADMIN_TOKEN });
    const dl = screen.getByTestId('card-dead-letters');
    expect(await within(dl).findByRole('alert')).toHaveTextContent('Unexpected error');
    expect(within(dl).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(await within(screen.getByTestId('card-policy-bundle')).findByText('in sync')).toBeInTheDocument();
  });

  it('counts active and expiring exceptions', () => {
    // At NOW: exc-1 expires in 19 days, exc-2 in 2 days, exc-3 revoked.
    expect(exceptionCounts(exceptions, NOW)).toEqual({ active: 2, expiring: 1 });
  });
});
