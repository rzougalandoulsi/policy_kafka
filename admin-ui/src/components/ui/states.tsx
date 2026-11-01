import type { ReactNode } from 'react';
import { AlertTriangle, Inbox } from 'lucide-react';
import { Button } from './button';
import { describeError } from '@/api/client';

export function EmptyState({
  title,
  description,
  action,
}: {
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-12 text-center">
      <Inbox className="h-8 w-8 text-muted" aria-hidden />
      <p className="font-medium">{title}</p>
      {description && <p className="max-w-md text-sm text-muted">{description}</p>}
      {action}
    </div>
  );
}

export function ErrorState({
  error,
  onRetry,
  title = 'Could not load data',
}: {
  error: unknown;
  onRetry?: () => void;
  title?: ReactNode;
}) {
  return (
    <div role="alert" className="flex flex-col items-center justify-center gap-2 px-6 py-10 text-center">
      <AlertTriangle className="h-8 w-8 text-danger" aria-hidden />
      <p className="font-medium">{title}</p>
      <p className="max-w-lg text-sm text-muted">{describeError(error)}</p>
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry}>
          Retry
        </Button>
      )}
    </div>
  );
}

export function InlineAlert({
  tone = 'info',
  children,
}: {
  tone?: 'info' | 'warn' | 'danger' | 'ok';
  children: ReactNode;
}) {
  const cls = {
    info: 'bg-info-bg text-info',
    warn: 'bg-warn-bg text-warn',
    danger: 'bg-danger-bg text-danger',
    ok: 'bg-ok-bg text-ok',
  }[tone];
  return (
    <div role={tone === 'danger' ? 'alert' : 'status'} className={`rounded-md px-3 py-2 text-sm ${cls}`}>
      {children}
    </div>
  );
}
