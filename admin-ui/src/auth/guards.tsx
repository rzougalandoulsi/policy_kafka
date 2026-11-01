import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth } from './AuthProvider';
import { Spinner } from '@/components/ui/spinner';

export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'loading') return <Spinner label="Checking session" full />;
  if (status === 'anonymous')
    return <Navigate to="/login" replace state={{ returnTo: location.pathname + location.search }} />;
  return <>{children}</>;
}

/** Read access needs the auditor role (admins are auditors). */
export function RequireReader({ children }: { children: ReactNode }) {
  const { entitlements } = useAuth();
  if (!entitlements.canRead) return <Navigate to="/forbidden" replace />;
  return <>{children}</>;
}

/** Renders children only for admins; use for write controls. */
export function AdminOnly({ children, fallback = null }: { children: ReactNode; fallback?: ReactNode }) {
  const { entitlements } = useAuth();
  return <>{entitlements.canWrite ? children : fallback}</>;
}
