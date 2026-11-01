import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { renderApp } from '@/test/render';
import { ADMIN_TOKEN, AUDITOR_TOKEN, RELEASE_ID, bundles } from '@/test/fixtures';
import { BundlesPage, defaultReleaseSelection, groupByBundle, latestRetired } from './BundlesPage';

describe('BundlesPage', () => {
  it('shows the release banner and one group per bundle, newest version first', async () => {
    renderApp(<BundlesPage />, { token: ADMIN_TOKEN });
    const diag = await screen.findByTestId('diagnostics');
    expect(within(diag).getByText(RELEASE_ID)).toBeInTheDocument();
    expect(within(diag).getByText('in sync')).toBeInTheDocument();
    expect(within(diag).getByText('composition ready')).toBeInTheDocument();

    const release = await screen.findByTestId('release-card');
    expect(within(release).getByTestId('release-row-naming')).toHaveTextContent('naming-v2');

    const groups = screen.getAllByTestId(/bundle-group-/);
    expect(groups.map((g) => g.dataset.testid)).toEqual([
      'bundle-group-core',
      'bundle-group-manifests',
      'bundle-group-naming',
      'bundle-group-quotas',
      'bundle-group-resources',
      'bundle-group-changes',
    ]);
    const core = screen.getByTestId('bundle-group-core');
    const rows = within(core).getAllByTestId(/bundle-row-/);
    expect(rows.map((r) => r.dataset.testid)).toEqual([
      'bundle-row-core-v3',
      'bundle-row-core-v2',
      'bundle-row-core-v1',
    ]);
    const failed = screen.getByTestId('bundle-row-core-v3');
    expect(within(failed).getByText(/OPA did not load revision/)).toBeInTheDocument();
    expect(within(failed).queryByRole('button', { name: 'Activate' })).not.toBeInTheDocument();
  });

  it('hides write controls from auditors', async () => {
    renderApp(<BundlesPage />, { token: AUDITOR_TOKEN });
    await screen.findByTestId('bundle-row-core-v1');
    expect(screen.queryByRole('button', { name: /Activate/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Upload bundles/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Rollback/ })).not.toBeInTheDocument();
  });

  it('activates a retired version after confirmation and watches that bundle', async () => {
    const user = userEvent.setup();
    const calls: string[] = [];
    server.use(
      http.post('/api/v1/admin/policy-bundles/:id/activate', ({ params }) => {
        calls.push(String(params.id));
        return HttpResponse.json({ ...bundles[0], status: 'ACTIVE' });
      }),
    );
    renderApp(<BundlesPage />, { token: ADMIN_TOKEN });
    const row = await screen.findByTestId('bundle-row-core-v1');
    await user.click(within(row).getByRole('button', { name: 'Activate' }));
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByRole('heading', { name: /Activate core revision core-v1/ }),
    ).toBeInTheDocument();
    expect(within(dialog).getByText(/within 90 seconds/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Activate' }));
    await waitFor(() => expect(calls).toEqual(['bundle-1']));
    expect(await screen.findByTestId('activation-watcher')).toHaveTextContent(
      'Waiting for OPA to adopt core revision core-v1',
    );
  });

  it('rolls back one bundle and sends its name as the query parameter', async () => {
    const user = userEvent.setup();
    const queries: string[] = [];
    server.use(
      http.post('/api/v1/admin/policy-bundles/rollback', ({ request }) => {
        queries.push(new URL(request.url).search);
        return HttpResponse.json({ ...bundles[0], status: 'ACTIVE' });
      }),
    );
    renderApp(<BundlesPage />, { token: ADMIN_TOKEN });
    const core = await screen.findByTestId('bundle-group-core');
    await user.click(within(core).getByRole('button', { name: /Rollback/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: /Roll back core/ })).toBeInTheDocument();
    expect(within(dialog).getByText(/core-v1/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Roll back' }));
    await waitFor(() => expect(queries).toEqual(['?bundle=core']));
    // naming has no retired version: its rollback button is disabled
    const naming = screen.getByTestId('bundle-group-naming');
    expect(within(naming).getByRole('button', { name: /Rollback/ })).toBeDisabled();
  });

  it('activates a full release from the default selection (newest upload per bundle)', async () => {
    const user = userEvent.setup();
    let body: { versionIds: string[] } | null = null;
    server.use(
      http.post('/api/v1/admin/policy-releases', async ({ request }) => {
        body = (await request.json()) as { versionIds: string[] };
        return HttpResponse.json({ id: 'rel-new', revisions: { naming: 'naming-v3' } });
      }),
    );
    renderApp(<BundlesPage />, { token: ADMIN_TOKEN });
    await screen.findByTestId('bundle-group-core');
    await user.click(screen.getByRole('button', { name: /Activate release/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByLabelText('naming')).toHaveValue('bundle-9');
    expect(within(dialog).getByText(/Changes:/)).toHaveTextContent('naming');
    await user.click(within(dialog).getByRole('button', { name: 'Activate release' }));
    await waitFor(() => expect(body).not.toBeNull());
    expect([...body!.versionIds].sort()).toEqual(
      ['bundle-2', 'bundle-4', 'bundle-5', 'bundle-6', 'bundle-7', 'bundle-8', 'bundle-9']
        .filter((id) => id !== 'bundle-5')
        .sort(),
    );
    expect(await screen.findByTestId('activation-watcher')).toHaveTextContent('naming revision naming-v3');
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
    expect(await screen.findByTestId('bundle-row-core-v2')).toBeInTheDocument();
  });

  it('groups by bundle, picks rollback targets per bundle and defaults the release selection', () => {
    expect(groupByBundle(bundles).map(([n]) => n)).toEqual([
      'core',
      'manifests',
      'naming',
      'quotas',
      'resources',
      'changes',
    ]);
    expect(latestRetired(bundles, 'core')?.revision).toBe('core-v1');
    expect(latestRetired(bundles, 'naming')).toBeUndefined();
    const sel = defaultReleaseSelection(bundles);
    expect(sel.naming).toBe('bundle-9');
    expect(sel.core).toBe('bundle-2');
    expect(Object.keys(sel)).toHaveLength(6);
  });
});
