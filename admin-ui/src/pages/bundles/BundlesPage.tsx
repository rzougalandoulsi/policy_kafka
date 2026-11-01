import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import { Upload, Undo2 } from 'lucide-react';
import { describeError } from '@/api/client';
import { useActivateBundle, useBundles, useRollbackBundle } from '@/api/hooks';
import type { PolicyBundleVersion } from '@/api/types';
import { AdminOnly } from '@/auth/guards';
import { Badge, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { ConfirmDialog } from '@/components/ui/confirm-dialog';
import { PageHeader } from '@/components/ui/page';
import { Spinner } from '@/components/ui/spinner';
import { EmptyState, ErrorState } from '@/components/ui/states';
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table';
import { formatInstant } from '@/lib/time';
import { shortSha } from '@/lib/utils';
import { ActivationWatcher } from './ActivationWatcher';
import { DiagnosticsBanner } from './DiagnosticsBanner';
import { UploadBundleDialog } from './UploadBundleDialog';

export function latestRetired(bundles: PolicyBundleVersion[]): PolicyBundleVersion | undefined {
  return bundles
    .filter((b) => b.status === 'RETIRED' && b.retiredAt)
    .sort((a, b) => (b.retiredAt ?? '').localeCompare(a.retiredAt ?? ''))[0];
}

export function BundlesPage() {
  const bundles = useBundles();
  const activate = useActivateBundle();
  const rollback = useRollbackBundle();
  const [uploadOpen, setUploadOpen] = useState(false);
  const [activateTarget, setActivateTarget] = useState<PolicyBundleVersion | null>(null);
  const [rollbackOpen, setRollbackOpen] = useState(false);
  const [watching, setWatching] = useState<string | null>(null);

  const sorted = useMemo(
    () => [...(bundles.data ?? [])].sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt)),
    [bundles.data],
  );
  const active = sorted.find((b) => b.status === 'ACTIVE');
  const rollbackTarget = latestRetired(sorted);

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Policy bundles"
        description="Versioned Rego bundles served to OPA. Exactly one is ACTIVE; activation is verified against the revision OPA reports and reverted automatically if OPA does not adopt it within 90 seconds."
        actions={
          <AdminOnly>
            <Button
              variant="secondary"
              onClick={() => setRollbackOpen(true)}
              disabled={!rollbackTarget}
              title={rollbackTarget ? undefined : 'No retired bundle to roll back to'}
            >
              <Undo2 className="h-4 w-4" aria-hidden /> Rollback
            </Button>
            <Button onClick={() => setUploadOpen(true)}>
              <Upload className="h-4 w-4" aria-hidden /> Upload bundle
            </Button>
          </AdminOnly>
        }
      />
      <DiagnosticsBanner />
      {watching && <ActivationWatcher targetRevision={watching} onDismiss={() => setWatching(null)} />}

      <Card>
        {bundles.isPending ? (
          <Spinner label="Loading bundles" className="p-6" />
        ) : bundles.isError ? (
          <ErrorState error={bundles.error} onRetry={() => bundles.refetch()} />
        ) : sorted.length === 0 ? (
          <EmptyState
            title="No bundles uploaded"
            description="Build one with policies/build.sh and upload it here, or run deploy/scripts/upload-bundle.sh."
          />
        ) : (
          <Table>
            <THead>
              <TR>
                <TH>Revision</TH>
                <TH>Status</TH>
                <TH>SHA-256</TH>
                <TH>Tests</TH>
                <TH>Comment</TH>
                <TH>Uploaded</TH>
                <TH>Activated</TH>
                <TH>Failure</TH>
                <TH>
                  <span className="sr-only">Actions</span>
                </TH>
              </TR>
            </THead>
            <TBody>
              {sorted.map((b) => (
                <TR key={b.id} data-testid={`bundle-row-${b.revision}`}>
                  <TD className="mono font-medium">{b.revision}</TD>
                  <TD>
                    <StatusBadge status={b.status} />
                  </TD>
                  <TD className="mono text-xs" title={b.sha256}>
                    {shortSha(b.sha256)}
                  </TD>
                  <TD>
                    {b.testReport?.passed != null ? (
                      <Badge tone="ok">{b.testReport.passed} passed</Badge>
                    ) : (
                      <Badge tone="warn">none</Badge>
                    )}
                  </TD>
                  <TD className="max-w-xs truncate" title={b.comment ?? undefined}>
                    {b.comment ?? '—'}
                  </TD>
                  <TD>
                    <div>{formatInstant(b.uploadedAt)}</div>
                    <div className="text-xs text-muted">{b.uploadedBy}</div>
                  </TD>
                  <TD>{formatInstant(b.activatedAt)}</TD>
                  <TD className="max-w-xs text-xs text-danger">{b.failureReason ?? ''}</TD>
                  <TD className="text-right">
                    <AdminOnly>
                      {(b.status === 'UPLOADED' || b.status === 'RETIRED') && (
                        <Button size="sm" variant="secondary" onClick={() => setActivateTarget(b)}>
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

      <UploadBundleDialog
        open={uploadOpen}
        onOpenChange={setUploadOpen}
        onActivated={(rev) => setWatching(rev)}
      />

      <ConfirmDialog
        open={!!activateTarget}
        onOpenChange={(o) => !o && setActivateTarget(null)}
        title={`Activate revision ${activateTarget?.revision ?? ''}?`}
        confirmLabel="Activate"
        onConfirm={async () => {
          if (!activateTarget) return;
          try {
            const v = await activate.mutateAsync(activateTarget.id);
            toast.success(`Activated revision ${v.revision}; waiting for OPA`);
            setWatching(v.revision);
          } catch (e) {
            toast.error(describeError(e));
            throw e;
          }
        }}
      >
        <p className="text-sm">
          {active ? (
            <>
              The current ACTIVE revision <code>{active.revision}</code> will be retired.{' '}
            </>
          ) : (
            'No bundle is active at the moment. '
          )}
          OPA polls the bundle endpoint and must adopt <code>{activateTarget?.revision}</code> within 90
          seconds; otherwise Governance marks it FAILED and restores the previous version automatically. Every
          decision made meanwhile records the revision it was evaluated against.
        </p>
      </ConfirmDialog>

      <ConfirmDialog
        open={rollbackOpen}
        onOpenChange={setRollbackOpen}
        title="Roll back to the previous bundle?"
        confirmLabel="Roll back"
        tone="danger"
        disabled={!rollbackTarget}
        onConfirm={async () => {
          try {
            const v = await rollback.mutateAsync();
            toast.success(`Rolled back to revision ${v.revision}; waiting for OPA`);
            setWatching(v.revision);
          } catch (e) {
            toast.error(describeError(e));
            throw e;
          }
        }}
      >
        <p className="text-sm">
          {rollbackTarget ? (
            <>
              Re-activates the most recently retired revision <code>{rollbackTarget.revision}</code> (retired{' '}
              {formatInstant(rollbackTarget.retiredAt)}) and retires{' '}
              <code>{active?.revision ?? 'the current version'}</code>. OPA adoption is verified the same way
              as an activation.
            </>
          ) : (
            'There is no retired bundle to roll back to.'
          )}
        </p>
      </ConfirmDialog>
    </div>
  );
}
