import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import { describeError } from '@/api/client';
import { useActivateApprovalConfig, useApprovalConfigs } from '@/api/hooks';
import type { ApprovalConfiguration } from '@/api/types';
import { AdminOnly } from '@/auth/guards';
import { Badge, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardBody, CardHeader } from '@/components/ui/card';
import { ConfirmDialog } from '@/components/ui/confirm-dialog';
import { KV } from '@/components/ui/kv';
import { PageHeader } from '@/components/ui/page';
import { Spinner } from '@/components/ui/spinner';
import { EmptyState, ErrorState, InlineAlert } from '@/components/ui/states';
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table';
import { formatInstant } from '@/lib/time';
import { describeRule } from './approvalRuleSchema';
import { NewApprovalRuleDialog } from './NewApprovalRuleDialog';

export function ApprovalRulesPage() {
  const query = useApprovalConfigs();
  const activate = useActivateApprovalConfig();
  const [creating, setCreating] = useState(false);
  const [toActivate, setToActivate] = useState<ApprovalConfiguration | null>(null);

  const versions = useMemo(() => [...(query.data ?? [])].sort((a, b) => b.version - a.version), [query.data]);
  const active = versions.find((v) => v.status === 'ACTIVE');

  return (
    <>
      <PageHeader
        title="Approval rules"
        description="Who may approve, how many approvals are needed, whether requesters are excluded, and how long an approval stays open. Exactly one version is active."
        actions={
          <AdminOnly>
            <Button onClick={() => setCreating(true)}>New version</Button>
          </AdminOnly>
        }
      />

      {query.isPending ? (
        <Spinner label="Loading approval rules" />
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : (
        <div className="flex flex-col gap-5">
          <Card>
            <CardHeader title="Active rule" description={active ? `Version ${active.version}` : undefined} />
            <CardBody>
              {active ? (
                <div className="flex flex-col gap-4">
                  <KV
                    items={[
                      {
                        k: 'Approver roles',
                        v: (
                          <span className="flex flex-wrap gap-1">
                            {active.approverRoles.map((r) => (
                              <Badge key={r} tone="info">
                                {r}
                              </Badge>
                            ))}
                          </span>
                        ),
                      },
                      { k: 'Required approvals', v: active.requiredApprovals },
                      {
                        k: 'Separation of duties',
                        v: active.separationOfDuties
                          ? 'Yes, requesters cannot approve their own request'
                          : 'No',
                      },
                      { k: 'Time to decide', v: `${active.ttlHours} hours` },
                      { k: 'Activated at', v: formatInstant(active.activatedAt) },
                      { k: 'Created by', v: active.createdBy },
                    ]}
                  />
                  <InlineAlert tone="info">{describeRule(active)}</InlineAlert>
                </div>
              ) : (
                <InlineAlert tone="warn">
                  No active approval rule. Approvals cannot be opened until one is activated.
                </InlineAlert>
              )}
            </CardBody>
          </Card>

          <Card>
            <CardHeader
              title="History"
              description="All versions, newest first. Activating an older version is how you roll back."
            />
            {versions.length === 0 ? (
              <EmptyState title="No approval rules yet" />
            ) : (
              <Table aria-label="Approval rule versions">
                <THead>
                  <TR>
                    <TH>Version</TH>
                    <TH>Status</TH>
                    <TH>Roles</TH>
                    <TH>Quorum</TH>
                    <TH>SoD</TH>
                    <TH>TTL</TH>
                    <TH>Created</TH>
                    <TH>Activated</TH>
                    <TH className="text-right">Actions</TH>
                  </TR>
                </THead>
                <TBody>
                  {versions.map((v) => (
                    <TR key={v.id}>
                      <TD className="font-medium">v{v.version}</TD>
                      <TD>
                        <StatusBadge status={v.status} />
                      </TD>
                      <TD>{v.approverRoles.join(', ')}</TD>
                      <TD>{v.requiredApprovals}</TD>
                      <TD>{v.separationOfDuties ? 'yes' : 'no'}</TD>
                      <TD>{v.ttlHours}h</TD>
                      <TD>
                        <div>{formatInstant(v.createdAt)}</div>
                        <div className="text-xs text-muted">{v.createdBy}</div>
                      </TD>
                      <TD>{formatInstant(v.activatedAt)}</TD>
                      <TD className="text-right">
                        {v.status !== 'ACTIVE' && (
                          <AdminOnly>
                            <Button size="sm" variant="secondary" onClick={() => setToActivate(v)}>
                              Activate
                            </Button>
                          </AdminOnly>
                        )}
                      </TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            )}
          </Card>
        </div>
      )}

      <NewApprovalRuleDialog open={creating} onOpenChange={setCreating} active={active} />

      <ConfirmDialog
        open={toActivate !== null}
        onOpenChange={(o) => !o && setToActivate(null)}
        title={toActivate ? `Activate approval rule v${toActivate.version}?` : ''}
        description={
          active
            ? `This retires v${active.version}. There is no separate rollback: to undo, activate v${active.version} again. Approvals already open keep the rule they were opened with.`
            : 'This becomes the active rule for approvals opened from now on.'
        }
        confirmLabel="Activate"
        onConfirm={async () => {
          if (!toActivate) return;
          try {
            await activate.mutateAsync(toActivate.id);
            toast.success(`Approval rule v${toActivate.version} is now active`);
          } catch (e) {
            toast.error(describeError(e));
            throw e;
          }
        }}
      >
        {toActivate && <InlineAlert tone="info">{describeRule(toActivate)}</InlineAlert>}
      </ConfirmDialog>
    </>
  );
}
