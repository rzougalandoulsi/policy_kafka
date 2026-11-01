import { screen } from '@testing-library/react';
import { renderApp } from '@/test/render';
import { ADMIN_TOKEN, AUDITOR_TOKEN, DEV_TOKEN } from '@/test/fixtures';
import { RequireAuth, RequireReader, AdminOnly } from './guards';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { useExceptions } from '@/api/hooks';
import { exceptions } from '@/test/fixtures';

function FirstQuery() {
  const q = useExceptions();
  return <div>{q.isSuccess ? `rows:${q.data.length}` : q.isError ? 'query failed' : 'loading'}</div>;
}

function Probe() {
  return (
    <RequireAuth>
      <RequireReader>
        <div>reader content</div>
        <AdminOnly fallback={<div>read only</div>}>
          <button>Write</button>
        </AdminOnly>
      </RequireReader>
    </RequireAuth>
  );
}

describe('auth guards', () => {
  it('shows write controls to admins', async () => {
    renderApp(<Probe />, { token: ADMIN_TOKEN });
    expect(await screen.findByText('reader content')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Write' })).toBeInTheDocument();
  });
  it('hides write controls from auditors', async () => {
    renderApp(<Probe />, { token: AUDITOR_TOKEN });
    expect(await screen.findByText('reader content')).toBeInTheDocument();
    expect(screen.getByText('read only')).toBeInTheDocument();
  });
  it('redirects users without a governance role', async () => {
    renderApp(<Probe />, { token: DEV_TOKEN, path: '/' });
    expect(await screen.findByTestId('elsewhere')).toBeInTheDocument();
  });
  it('sends the bearer token on the very first query after boot (regression: reload logged the user out)', async () => {
    server.use(
      http.get('/api/v1/admin/exceptions', ({ request }) =>
        request.headers.get('authorization')
          ? HttpResponse.json(exceptions)
          : new HttpResponse(null, { status: 401 }),
      ),
    );
    renderApp(
      <RequireAuth>
        <FirstQuery />
      </RequireAuth>,
      { token: ADMIN_TOKEN, path: '/' },
    );
    expect(await screen.findByText('rows:3')).toBeInTheDocument();
    expect(screen.queryByTestId('elsewhere')).not.toBeInTheDocument();
  });

  it('redirects anonymous users to login', async () => {
    renderApp(<Probe />, { token: null, path: '/' });
    expect(await screen.findByTestId('elsewhere')).toBeInTheDocument();
  });
});
