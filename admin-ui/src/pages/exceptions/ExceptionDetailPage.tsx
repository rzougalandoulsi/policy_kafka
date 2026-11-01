import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { toast } from 'sonner';
import { describeError } from '@/api/client';
import { useAudit, useException, useRevokeException } from '@/api/hooks';
import { AdminOnly } from '@/auth/guards';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardBody, CardHeader } from '@/components/ui/card';
import { ConfirmDialog } from '@/components/ui/confirm-dialog';
import { Field, Textarea } from '@/components/ui/form';
import { JsonView } from '@/components/ui/json-view';
import { KV } from '@/components/ui/kv';
import { PageHeader } from '@/components/ui/page';
import { Spinner } from '@/components/ui/spinner';
import { EmptyState, ErrorState } from '@/components/ui/states';
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table';
import { formatInstant, relative } from '@/lib/time';
import { LIFECYCLE_LABEL, LIFECYCLE_TONE, lifecycleOf } from './lifecycle';

export function ExceptionDetailPage() {
  const { id = '' } = useParams();
  const query = useException(id, { enabled: !!id });
  const audit = useAudit({ targetType: 'policy_exception', targetId: id }, { enabled: !!id });
  const revoke = useRevokeException();
  const [revokeOpen, setRevokeOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [expanded, setExpanded] = useState<number | null>(null);

  if (query.isLoading) return <Spinner label="Loading exception" full />;
  if (query.isError || !query.data)
    return <ErrorState error={query.error} onRetry={() => query.refetch()} title="Exception not found" />;

  const e = query.data;
  const lifecycle = lifecycleOf(e);

  return (
    <>
      <PageHeader
        title={
          <span className="flex flex-wrap items-center gap-2">
            <span className="mono">{e.policyId}</span>
            <Badge tone={LIFECYCLE_TONE[lifecycle]}>{LIFECYCLE_LABEL[lifecycle]}</Badge>
          </span>
        }
        description={
          <>
            <Link to="/exceptions" className="text-primary hover:underline">
              Exceptions
            </Link>{' '}
            / <span className="mono text-xs">{e.id}</span>
          </>
        }
        actions={
          !e.revokedAt && (
            <AdminOnly>
              <Button variant="danger" onClick={() => setRevokeOpen(true)}>
                Revoke
              </Button>
            </AdminOnly>
          )
        }
      />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader title="Scope" description="Who and what this exception covers." />
          <CardBody>
            <KV
              items={[
                { k: 'Policy', v: e.policyId, mono: true },
                { k: 'Team', v: e.teamId },
                { k: 'Environment', v: e.environment === '*' ? 'any (*)' : e.environment },
                { k: 'Principal', v: e.principalId ?? 'any principal of the team' },
                { k: 'Resource pattern', v: e.resourcePattern, mono: true },
                { k: 'Action types', v: e.actionTypes.length ? e.actionTypes.join(', ') : 'any' },
                { k: 'Reason', v: e.reason },
              ]}
            />
          </CardBody>
        </Card>
        <Card>
          <CardHeader title="Lifecycle" />
          <CardBody>
            <KV
              items={[
                { k: 'Expires at', v: `${formatInstant(e.expiresAt)} (${relative(e.expiresAt)})` },
                { k: 'Created by', v: e.createdBy },
                { k: 'Created at', v: formatInstant(e.createdAt) },
                { k: 'Revoked by', v: e.revokedBy ?? '—' },
                { k: 'Revoked at', v: e.revokedAt ? formatInstant(e.revokedAt) : '—' },
                { k: 'Revoke reason', v: e.revokeReason ?? '—' },
              ]}
            />
          </CardBody>
        </Card>
      </div>

      <Card className="mt-4">
        <CardHeader title="Audit trail" description="Every recorded change to this exception." />
        {audit.isLoading ? (
          <Spinner label="Loading audit" className="px-5 py-6" />
        ) : audit.isError ? (
          <ErrorState error={audit.error} onRetry={() => audit.refetch()} />
        ) : (audit.data ?? []).length === 0 ? (
          <EmptyState title="No audit entries" />
        ) : (
          <Table>
            <THead>
              <TR>
                <TH>Time</TH>
                <TH>Actor</TH>
                <TH>Action</TH>
                <TH>Details</TH>
              </TR>
            </THead>
            <TBody>
              {(audit.data ?? []).map((a) => (
                <TR key={a.id}>
                  <TD className="whitespace-nowrap text-xs">{formatInstant(a.occurredAt)}</TD>
                  <TD className="text-xs">{a.actorId}</TD>
                  <TD className="mono text-xs">{a.action}</TD>
                  <TD>
                    <Button
                      variant="link"
                      size="sm"
                      onClick={() => setExpanded(expanded === a.id ? null : a.id)}
                      aria-expanded={expanded === a.id}
                    >
                      {expanded === a.id ? 'Hide details' : 'Show details'}
                    </Button>
                    {expanded === a.id && <JsonView value={a.details} className="mt-2" maxHeight="12rem" />}
                  </TD>
                </TR>
              ))}
            </TBody>
          </Table>
        )}
      </Card>

      <ConfirmDialog
        open={revokeOpen}
        onOpenChange={(o) => {
          setRevokeOpen(o);
          if (!o) setReason('');
        }}
        title="Revoke this exception?"
        description="Requests evaluated after revocation will no longer be exempted from this policy. This cannot be undone."
        confirmLabel="Revoke"
        tone="danger"
        onConfirm={async () => {
          try {
            await revoke.mutateAsync({ id: e.id, reason: reason.trim() || undefined });
            toast.success('Exception revoked');
            await Promise.all([query.refetch(), audit.refetch()]);
          } catch (err) {
            toast.error(describeError(err));
            throw err;
          }
        }}
      >
        <Field id="revoke-reason" label="Reason" hint="Optional, recorded in the audit trail.">
          <Textarea
            id="revoke-reason"
            value={reason}
            onChange={(ev) => setReason(ev.target.value)}
            rows={3}
          />
        </Field>
      </ConfirmDialog>
    </>
  );
}
