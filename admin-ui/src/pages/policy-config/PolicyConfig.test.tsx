import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { renderApp } from '@/test/render';
import { server } from '@/test/server';
import { ADMIN_TOKEN, AUDITOR_TOKEN, policyConfigs } from '@/test/fixtures';
import { ConfigDiff } from './ConfigDiff';
import { PolicyConfigPage } from './PolicyConfigPage';
import { NewPolicyConfigPage } from './NewPolicyConfigPage';
import { PolicyConfigDetailPage } from './PolicyConfigDetailPage';

describe('ConfigDiff', () => {
  it('renders added, removed and changed rows', () => {
    renderApp(
      <ConfigDiff before={{ a: 1, gone: true, same: 'x' }} after={{ a: 2, added: [1], same: 'x' }} />,
    );
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows.map((r) => r.getAttribute('data-kind'))).toEqual(['changed', 'added', 'removed']);
    expect(within(rows[0]).getByText('$.a')).toBeInTheDocument();
  });
  it('says so when nothing differs', () => {
    renderApp(<ConfigDiff before={{ a: 1 }} after={{ a: 1 }} />);
    expect(screen.getByText('No differences.')).toBeInTheDocument();
  });
});

describe('PolicyConfigPage', () => {
  it('lists versions newest first and highlights the ACTIVE one', async () => {
    renderApp(<PolicyConfigPage />, { token: AUDITOR_TOKEN });
    expect(await screen.findByText('v3')).toBeInTheDocument();
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows.map((r) => within(r).getByRole('link').textContent)).toEqual(['v3', 'v2', 'v1']);
    expect(
      rows.filter((r) => r.hasAttribute('data-active')).map((r) => within(r).getByRole('link').textContent),
    ).toEqual(['v2']);
    expect(screen.queryByRole('button', { name: 'Activate' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Rollback' })).not.toBeInTheDocument();
  });

  it('lets an admin activate a draft after reviewing the diff', async () => {
    const user = userEvent.setup();
    const activated: string[] = [];
    server.use(
      http.post('/api/v1/admin/policy-configurations/:id/activate', ({ params }) => {
        activated.push(String(params.id));
        return HttpResponse.json({ ...policyConfigs[2], status: 'ACTIVE' });
      }),
    );
    renderApp(<PolicyConfigPage />, { token: ADMIN_TOKEN });
    await screen.findByText('v3');
    const buttons = screen.getAllByRole('button', { name: 'Activate' });
    expect(buttons).toHaveLength(2); // DRAFT v3 and RETIRED v1
    await user.click(buttons[0]);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/retires the current ACTIVE v2/)).toBeInTheDocument();
    expect(within(dialog).getByText('$.limits.maxPartitionsPerTopic')).toBeInTheDocument();
    expect(within(dialog).getByText('$.limits.maxTopicsPerManifest')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Activate' }));
    await waitFor(() => expect(activated).toEqual(['cfg-3']));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('rolls back to the most recently retired version', async () => {
    const user = userEvent.setup();
    let rolledBack = 0;
    server.use(
      http.post(
        '/api/v1/admin/policy-configurations/rollback',
        () => (rolledBack++, HttpResponse.json({ ...policyConfigs[0], status: 'ACTIVE' })),
      ),
    );
    renderApp(<PolicyConfigPage />, { token: ADMIN_TOKEN });
    await screen.findByText('v3');
    await user.click(screen.getByRole('button', { name: 'Rollback' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Roll back to version 1')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Activate' }));
    await waitFor(() => expect(rolledBack).toBe(1));
  });
});

describe('PolicyConfigDetailPage', () => {
  it('shows metadata, sections and a comparison against another version', async () => {
    const user = userEvent.setup();
    renderApp(<PolicyConfigDetailPage />, {
      route: '/policy-configurations/cfg-3',
      path: '/policy-configurations/:id',
      token: AUDITOR_TOKEN,
    });
    expect(await screen.findByText('Policy configuration v3')).toBeInTheDocument();
    expect(screen.getByText('maxTopicsPerManifest')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Activate' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Compare' }));
    expect(await screen.findByText('$.limits.maxTopicsPerManifest')).toBeInTheDocument(); // defaults to ACTIVE v2
  });
});

describe('NewPolicyConfigPage', () => {
  const mount = () =>
    renderApp(<NewPolicyConfigPage />, {
      route: '/policy-configurations/new',
      path: '/policy-configurations/new',
      token: ADMIN_TOKEN,
    });

  it('keeps the form and raw JSON in sync and posts the draft', async () => {
    const user = userEvent.setup();
    const posted: Array<{
      content: Record<string, { maxPartitionsPerTopic?: number }>;
      comment: string | null;
    }> = [];
    server.use(
      http.post('/api/v1/admin/policy-configurations', async ({ request }) => {
        posted.push((await request.json()) as (typeof posted)[number]);
        return HttpResponse.json({ ...policyConfigs[2], id: 'cfg-new', version: 4 });
      }),
    );
    mount();
    expect(await screen.findByText(/Starting from v2/)).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'limits' }));
    const input = document.getElementById('limits-maxPartitionsPerTopic') as HTMLInputElement;
    expect(input.value).toBe('96');
    await user.clear(input);
    await user.type(input, '128');
    // live diff vs active
    expect(screen.getByText('$.limits.maxPartitionsPerTopic')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Raw JSON' }));
    const textarea = screen.getByLabelText('Configuration JSON') as HTMLTextAreaElement;
    expect(textarea.value).toContain('"maxPartitionsPerTopic": 128');
    await user.type(screen.getByLabelText('Comment'), 'bump cap');
    await user.click(screen.getByRole('button', { name: 'Create draft' }));
    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0].content.limits.maxPartitionsPerTopic).toBe(128);
    expect(posted[0].comment).toBe('bump cap');
    expect(await screen.findByTestId('elsewhere')).toBeInTheDocument(); // navigated to the detail page
  });

  it('blocks submission on invalid JSON or invalid limits', async () => {
    const user = userEvent.setup();
    mount();
    await screen.findByText(/Starting from v2/);
    await user.click(screen.getByRole('tab', { name: 'Raw JSON' }));
    const textarea = screen.getByLabelText('Configuration JSON');
    fireEvent.change(textarea, { target: { value: '{ not json' } });
    expect(await screen.findByRole('alert')).toHaveTextContent(/Invalid JSON/);
    expect(screen.getByRole('button', { name: 'Create draft' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Create and activate' })).toBeDisabled();

    fireEvent.change(textarea, {
      target: {
        value: JSON.stringify({
          ...policyConfigs[1].content,
          limits: { ...policyConfigs[1].content.limits, maxRetentionMs: -5 },
        }),
      },
    });
    expect(await screen.findByText('limits.maxRetentionMs must be a positive number')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create draft' })).toBeDisabled();

    fireEvent.change(textarea, { target: { value: JSON.stringify(policyConfigs[1].content) } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Create draft' })).toBeEnabled());
  });
});
