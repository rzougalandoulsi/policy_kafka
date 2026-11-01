import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { renderApp } from '@/test/render';
import { ADMIN_TOKEN, AUDITOR_TOKEN, bundles } from '@/test/fixtures';
import { BundlesPage, latestRetired } from './BundlesPage';

describe('BundlesPage', () => {
  it('shows the diagnostics banner and one row per bundle', async () => {
    renderApp(<BundlesPage />, { token: ADMIN_TOKEN });
    const diag = await screen.findByTestId('diagnostics');
    expect(within(diag).getByText('in sync')).toBeInTheDocument();
    expect(within(diag).getAllByText('v2')).toHaveLength(2);
    expect(await screen.findByTestId('bundle-row-v1')).toBeInTheDocument();
    expect(screen.getByTestId('bundle-row-v2')).toBeInTheDocument();
    const failed = screen.getByTestId('bundle-row-v3');
    expect(within(failed).getByText(/OPA did not load revision/)).toBeInTheDocument();
    expect(within(failed).queryByRole('button', { name: 'Activate' })).not.toBeInTheDocument();
    // newest first
    const rows = screen.getAllByTestId(/bundle-row-/);
    expect(rows.map((r) => r.dataset.testid)).toEqual(['bundle-row-v3', 'bundle-row-v2', 'bundle-row-v1']);
  });

  it('hides write controls from auditors', async () => {
    renderApp(<BundlesPage />, { token: AUDITOR_TOKEN });
    await screen.findByTestId('bundle-row-v1');
    expect(screen.queryByRole('button', { name: /Activate/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Upload bundle/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Rollback/ })).not.toBeInTheDocument();
  });

  it('activates a retired bundle after confirmation and starts watching OPA', async () => {
    const user = userEvent.setup();
    const calls: string[] = [];
    server.use(
      http.post('/api/v1/admin/policy-bundles/:id/activate', ({ params }) => {
        calls.push(String(params.id));
        return HttpResponse.json({ ...bundles[0], status: 'ACTIVE' });
      }),
    );
    renderApp(<BundlesPage />, { token: ADMIN_TOKEN });
    const row = await screen.findByTestId('bundle-row-v1');
    await user.click(within(row).getByRole('button', { name: 'Activate' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: /Activate revision v1/ })).toBeInTheDocument();
    expect(within(dialog).getByText(/within 90 seconds/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Activate' }));
    await waitFor(() => expect(calls).toEqual(['bundle-1']));
    expect(await screen.findByTestId('activation-watcher')).toHaveTextContent(
      'Waiting for OPA to adopt revision v1',
    );
  });

  it('shows an unavailable banner when diagnostics fail but still lists bundles', async () => {
    server.use(
      http.get('/api/v1/admin/policy-bundles/diagnostics', () =>
        HttpResponse.json(
          { code: 'POLICY_ENGINE_UNAVAILABLE', message: 'OPA unreachable', errors: [], details: {} },
          { status: 503 },
        ),
      ),
    );
    renderApp(<BundlesPage />, { token: ADMIN_TOKEN });
    expect(await screen.findByText(/Diagnostics unavailable/)).toBeInTheDocument();
    expect(await screen.findByTestId('bundle-row-v2')).toBeInTheDocument();
  });

  it('picks the most recently retired bundle as rollback target', () => {
    expect(latestRetired(bundles)?.revision).toBe('v1');
    expect(latestRetired(bundles.filter((b) => b.status !== 'RETIRED'))).toBeUndefined();
  });
});
