import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { Toaster } from 'sonner';
import { AuthProvider } from '@/auth/AuthProvider';
import { RequireAuth, RequireReader } from '@/auth/guards';
import { AppShell } from '@/components/layout/AppShell';
import { ApiError } from '@/api/client';
import { LoginPage } from '@/pages/LoginPage';
import { CallbackPage } from '@/pages/CallbackPage';
import { ForbiddenPage, NotFoundPage } from '@/pages/StatusPages';
import { OverviewPage } from '@/pages/overview/OverviewPage';
import { ExceptionsPage } from '@/pages/exceptions/ExceptionsPage';
import { ExceptionDetailPage } from '@/pages/exceptions/ExceptionDetailPage';
import { PolicyConfigPage } from '@/pages/policy-config/PolicyConfigPage';
import { PolicyConfigDetailPage } from '@/pages/policy-config/PolicyConfigDetailPage';
import { NewPolicyConfigPage } from '@/pages/policy-config/NewPolicyConfigPage';
import { BundlesPage } from '@/pages/bundles/BundlesPage';
import { ApprovalRulesPage } from '@/pages/approval-rules/ApprovalRulesPage';
import { AuditPage } from '@/pages/audit/AuditPage';
import { TeamsPage } from '@/pages/teams/TeamsPage';
import { TeamDetailPage } from '@/pages/teams/TeamDetailPage';
import { ShadowFindingsPage } from '@/pages/shadow/ShadowFindingsPage';

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
        refetchOnWindowFocus: false,
        staleTime: 10_000,
      },
    },
  });
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/callback" element={<CallbackPage />} />
      <Route
        path="/forbidden"
        element={
          <RequireAuth>
            <ForbiddenPage />
          </RequireAuth>
        }
      />
      <Route
        element={
          <RequireAuth>
            <RequireReader>
              <AppShell />
            </RequireReader>
          </RequireAuth>
        }
      >
        <Route index element={<OverviewPage />} />
        <Route path="teams" element={<TeamsPage />} />
        <Route path="teams/:teamId" element={<TeamDetailPage />} />
        <Route path="shadow-findings" element={<ShadowFindingsPage />} />
        <Route path="exceptions" element={<ExceptionsPage />} />
        <Route path="exceptions/:id" element={<ExceptionDetailPage />} />
        <Route path="policy-configurations" element={<PolicyConfigPage />} />
        <Route path="policy-configurations/new" element={<NewPolicyConfigPage />} />
        <Route path="policy-configurations/:id" element={<PolicyConfigDetailPage />} />
        <Route path="policy-bundles" element={<BundlesPage />} />
        <Route path="approval-rules" element={<ApprovalRulesPage />} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={createQueryClient()}>
      <AuthProvider>
        <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <AppRoutes />
        </BrowserRouter>
        <Toaster richColors position="bottom-right" closeButton />
      </AuthProvider>
    </QueryClientProvider>
  );
}
