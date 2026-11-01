import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { PageHeader } from '@/components/ui/page';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/components/ui/badge';
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table';
import { EmptyState, ErrorState } from '@/components/ui/states';
import { Spinner } from '@/components/ui/spinner';
import { AdminOnly } from '@/auth/guards';
import { useActivatePolicyConfig, usePolicyConfigs, useRollbackPolicyConfig } from '@/api/hooks';
import { describeError } from '@/api/client';
import { formatInstant } from '@/lib/time';
import { cn } from '@/lib/utils';
import type { PolicyConfiguration } from '@/api/types';
import { ActivateDialog } from './ActivateDialog';

export function latestRetired(list: PolicyConfiguration[]): PolicyConfiguration | null {
  return (
    list
      .filter((c) => c.status === 'RETIRED' && c.retiredAt)
      .sort((a, b) => (b.retiredAt ?? '').localeCompare(a.retiredAt ?? ''))[0] ?? null
  );
}

export function PolicyConfigPage() {
  const navigate = useNavigate();
  const { data, isLoading, error, refetch } = usePolicyConfigs();
  const activate = useActivatePolicyConfig();
  const rollback = useRollbackPolicyConfig();
  const [target, setTarget] = useState<PolicyConfiguration | null>(null);
  const [rollbackOpen, setRollbackOpen] = useState(false);

  const sorted = useMemo(() => [...(data ?? [])].sort((a, b) => b.version - a.version), [data]);
  const active = sorted.find((c) => c.status === 'ACTIVE') ?? null;
  const previous = latestRetired(sorted);

  return (
    <>
      <PageHeader
        title="Policy configuration"
        description="Versioned runtime parameters (naming, RBAC, limits, destructive operations, approval switches) supplied to OPA as trusted data. Exactly one version is ACTIVE."
        actions={
          <AdminOnly>
            <Button
              variant="secondary"
              onClick={() => setRollbackOpen(true)}
              disabled={!previous}
              title={previous ? `Re-activate v${previous.version}` : 'No retired version to roll back to'}
            >
              Rollback
            </Button>
            <Button onClick={() => navigate('/policy-configurations/new')}>New version</Button>
          </AdminOnly>
        }
      />
      <Card>
        {isLoading ? (
          <Spinner className="p-6" />
        ) : error ? (
          <ErrorState error={error} onRetry={() => void refetch()} />
        ) : sorted.length === 0 ? (
          <EmptyState
            title="No configuration versions"
            description="Flyway seeds cfg-initial; if nothing is listed the database is empty."
          />
        ) : (
          <Table>
            <THead>
              <TR>
                <TH>Version</TH>
                <TH>Status</TH>
                <TH>Comment</TH>
                <TH>Created</TH>
                <TH>Activated</TH>
                <TH>Retired</TH>
                <TH className="text-right">Actions</TH>
              </TR>
            </THead>
            <TBody>
              {sorted.map((c) => (
                <TR
                  key={c.id}
                  className={cn(c.status === 'ACTIVE' && 'bg-ok-bg/40 font-medium')}
                  data-active={c.status === 'ACTIVE' || undefined}
                >
                  <TD>
                    <Link to={`/policy-configurations/${c.id}`} className="text-primary hover:underline">
                      v{c.version}
                    </Link>
                    <div className="mono text-xs text-muted">{c.id}</div>
                  </TD>
                  <TD>
                    <StatusBadge status={c.status} />
                  </TD>
                  <TD className="max-w-xs truncate" title={c.comment ?? undefined}>
                    {c.comment || <span className="text-muted">—</span>}
                  </TD>
                  <TD>
                    <div>{c.createdBy}</div>
                    <div className="text-xs text-muted">{formatInstant(c.createdAt)}</div>
                  </TD>
                  <TD className="text-xs">{formatInstant(c.activatedAt)}</TD>
                  <TD className="text-xs">{formatInstant(c.retiredAt)}</TD>
                  <TD className="text-right">
                    <AdminOnly>
                      {(c.status === 'DRAFT' || c.status === 'RETIRED') && (
                        <Button size="sm" variant="secondary" onClick={() => setTarget(c)}>
                          Activate
                        </Button>
                      )}
                    </AdminOnly>
                  </TD>
                </TR>
              ))}
            </TBody>
          </Table>
        )}
      </Card>

      <ActivateDialog
        open={!!target}
        onOpenChange={(o) => !o && setTarget(null)}
        target={target}
        active={active}
        onConfirm={async () => {
          if (!target) return;
          try {
            const r = await activate.mutateAsync(target.id);
            toast.success(`Version ${r.version} is now ACTIVE`);
          } catch (e) {
            toast.error(describeError(e));
            throw e;
          }
        }}
      />
      <ActivateDialog
        open={rollbackOpen && !!previous}
        onOpenChange={setRollbackOpen}
        target={previous}
        active={active}
        title={previous ? `Roll back to version ${previous.version}` : 'Rollback'}
        onConfirm={async () => {
          try {
            const r = await rollback.mutateAsync();
            toast.success(`Rolled back: version ${r.version} is ACTIVE`);
          } catch (e) {
            toast.error(describeError(e));
            throw e;
          }
        }}
      />
    </>
  );
}
