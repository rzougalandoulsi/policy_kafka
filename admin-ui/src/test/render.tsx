import { type ReactElement, type ReactNode } from 'react';
import { render, type RenderOptions } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '@/auth/AuthProvider';
import { loadConfig, type UiConfig } from '@/config';
import { ADMIN_TOKEN } from './fixtures';

export const testConfig = loadConfig({
  oidcIssuer: 'https://test',
  oidcClientId: 'admin-1',
  oidcScope: 'openid',
  oidcEndpointBase: undefined,
  rolesClaim: 'roles',
  adminRole: 'governance-admin',
  auditorRole: 'governance-auditor',
  devTokenLogin: true,
  localPolicyAdmin: true,
});

/**
 * Renders inside QueryClient + Auth + Router. The token is stored as a dev token so AuthProvider adopts it
 * on boot without an OIDC round-trip. `path` is the route pattern the element is mounted at.
 */
export function renderApp(
  ui: ReactElement,
  {
    token = ADMIN_TOKEN,
    route = '/',
    path = '*',
    config = testConfig,
    ...options
  }: RenderOptions & { token?: string | null; route?: string; path?: string; config?: UiConfig } = {},
) {
  if (token) sessionStorage.setItem('governance-ui.dev-token', token);
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>
      <AuthProvider cfg={config}>
        <MemoryRouter
          initialEntries={[route]}
          future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
        >
          <Routes>
            <Route path={path} element={children} />
            <Route path="*" element={<div data-testid="elsewhere" />} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
  return { qc, ...render(ui, { wrapper: Wrapper, ...options }) };
}
