import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { renderApp } from '@/test/render';
import { server } from '@/test/server';
import { AUDITOR_TOKEN, exceptions } from '@/test/fixtures';
import { ExceptionDetailPage } from './ExceptionDetailPage';

const target = exceptions[0];
const opts = { route: `/exceptions/${target.id}`, path: '/exceptions/:id' };

describe('ExceptionDetailPage', () => {
  it('renders every field and the audit trail', async () => {
    renderApp(<ExceptionDetailPage />, opts);
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('MAX_PARTITIONS_PER_TOPIC');
    expect(screen.getByText('Load test needs 24 partitions')).toBeInTheDocument();
    expect(screen.getByText('any principal of the team')).toBeInTheDocument();
    expect(screen.getByText('team-a.*')).toBeInTheDocument();
    expect(await screen.findByText('EXCEPTION_CREATED')).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('expands audit details', async () => {
    const user = userEvent.setup();
    renderApp(<ExceptionDetailPage />, opts);
    await user.click(await screen.findByRole('button', { name: 'Show details' }));
    expect(screen.getByText(/"policyId": "MAX_PARTITIONS_PER_TOPIC"/)).toBeInTheDocument();
  });

  it('revokes with a reason and shows the revoked state', async () => {
    const user = userEvent.setup();
    let revokeReason: string | null = null;
    let revoked = false;
    server.use(
      http.delete(`/api/v1/admin/exceptions/${target.id}`, ({ request }) => {
        revokeReason = new URL(request.url).searchParams.get('reason');
        revoked = true;
        return HttpResponse.json({
          ...target,
          revokedAt: '2026-11-01T12:00:00Z',
          revokedBy: 'admin-1',
          revokeReason,
        });
      }),
      http.get(`/api/v1/admin/exceptions/${target.id}`, () =>
        HttpResponse.json(
          revoked
            ? { ...target, revokedAt: '2026-11-01T12:00:00Z', revokedBy: 'admin-1', revokeReason }
            : target,
        ),
      ),
    );
    renderApp(<ExceptionDetailPage />, opts);
    await user.click(await screen.findByRole('button', { name: 'Revoke' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^Reason/), 'no longer needed');
    await user.click(within(dialog).getByRole('button', { name: 'Revoke' }));
    expect(await screen.findByText('Revoked')).toBeInTheDocument();
    expect(revokeReason).toBe('no longer needed');
    expect(screen.queryByRole('button', { name: 'Revoke' })).not.toBeInTheDocument();
    expect(screen.getByText('no longer needed')).toBeInTheDocument();
  });

  it('hides revoke from auditors', async () => {
    renderApp(<ExceptionDetailPage />, { ...opts, token: AUDITOR_TOKEN });
    await screen.findByRole('heading', { level: 1 });
    expect(screen.queryByRole('button', { name: 'Revoke' })).not.toBeInTheDocument();
  });

  it('shows an error state for unknown ids', async () => {
    renderApp(<ExceptionDetailPage />, { route: '/exceptions/exc-missing', path: '/exceptions/:id' });
    expect(await screen.findByRole('alert')).toHaveTextContent('Exception exc-missing not found');
  });
});
