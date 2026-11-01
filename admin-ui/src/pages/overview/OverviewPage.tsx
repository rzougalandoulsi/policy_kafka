import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { describeError } from '@/api/client';
import {
  useApprovalConfigs,
  useAudit,
  useDeadLetters,
  useDiagnostics,
  useExceptions,
  usePolicyConfigs,
  useResolveDeadLetter,
} from '@/api/hooks';
import type { PolicyException } from '@/api/types';
import { AdminOnly } from '@/auth/guards';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardBody, CardHeader } from '@/components/ui/card';
import { PageHeader } from '@/components/ui/page';
import { Spinner } from '@/components/ui/spinner';
import { formatInstant, relative } from '@/lib/time';
import { shortId } from '@/lib/utils';

const EXPIRING_WINDOW_MS = 7 * 24 * 3_600_000;

export function exceptionCounts(exceptions: PolicyException[], now = new Date()) {
  const t = now.getTime();
  const active = exceptions.filter((e) => e.revokedAt == null && new Date(e.expiresAt).getTime() > t);
  const expiring = active.filter((e) => new Date(e.expiresAt).getTime() - t <= EXPIRING_WINDOW_MS);
  return { active: active.length, expiring: expiring.length };
}

/** Card with its own loading/error state so one failing endpoint never blanks the page. */
function HealthCard({
  title,
  to,
  query,
  children,
}: {
  title: string;
  to: string;
  query: { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown };
  children: ReactNode;
}) {
  return (
    <Card data-testid={`card-${title.toLowerCase().replace(/\s+/g, '-')}`}>
      <CardHeader
        title={title}
        actions={
          <Link to={to} className="text-sm text-primary hover:underline">
            Open
          </Link>
        }
      />
      <CardBody className="min-h-24 text-sm">
        {query.isPending ? (
          <Spinner label={`Loading ${title.toLowerCase()}`} />
        ) : query.isError ? (
          <div role="alert" className="flex flex-col gap-2">
            <p className="text-danger">{describeError(query.error)}</p>
            <Button size="sm" variant="secondary" className="self-start" onClick={() => query.refetch()}>
              Retry
            </Button>
          </div>
        ) : (
          children
        )}
      </CardBody>
    </Card>
  );
}

function Row({ k, v }: { k: string; v: ReactNode }) {
  return (
    <div className="flex justify-between gap-4 py-0.5">
      <span className="text-muted">{k}</span>
      <span className="text-right">{v}</span>
    </div>
  );
}

export function OverviewPage() {
  const configs = usePolicyConfigs();
  const diagnostics = useDiagnostics({ refetchInterval: 15_000 });
  const approvals = useApprovalConfigs();
  const exceptions = useExceptions({});
  const deadLetters = useDeadLetters({ refetchInterval: 30_000 });
  const audit = useAudit({ limit: 10 });
  const resolve = useResolveDeadLetter();

  const activeConfig = configs.data?.find((c) => c.status === 'ACTIVE');
  const activeApproval = approvals.data?.find((a) => a.status === 'ACTIVE');
  const counts = exceptions.data ? exceptionCounts(exceptions.data) : null;
  const openDeadLetters = deadLetters.data?.filter((d) => d.resolvedAt == null) ?? [];

  return (
    <div>
      <PageHeader
        title="Overview"
        description="What Governance is enforcing right now, and what needs attention."
      />
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <HealthCard title="Policy configuration" to="/policy-configurations" query={configs}>
          {activeConfig ? (
            <>
              <Row k="Active version" v={<Badge tone="ok">v{activeConfig.version}</Badge>} />
              <Row k="Comment" v={activeConfig.comment ?? '—'} />
              <Row k="Activated" v={formatInstant(activeConfig.activatedAt)} />
              <Row k="Versions" v={configs.data?.length ?? 0} />
            </>
          ) : (
            <p className="text-danger">No ACTIVE policy configuration: evaluations fail.</p>
          )}
        </HealthCard>

        <HealthCard title="Policy bundle" to="/policy-bundles" query={diagnostics}>
          {diagnostics.data && (
            <>
              <Row k="Release" v={<code>{diagnostics.data.releaseId ?? 'none'}</code>} />
              <Row
                k="Bundles in sync"
                v={`${Object.values(diagnostics.data.bundles).filter((b) => b.match).length} / ${
                  Object.keys(diagnostics.data.bundles).length
                }`}
              />
              <Row
                k="Status"
                v={
                  !diagnostics.data.releaseId ? (
                    <Badge tone="warn">no release active</Badge>
                  ) : diagnostics.data.match && diagnostics.data.ready ? (
                    <Badge tone="ok">in sync</Badge>
                  ) : (
                    <Badge tone="danger">{diagnostics.data.match ? 'not ready' : 'mismatch'}</Badge>
                  )
                }
              />
            </>
          )}
        </HealthCard>

        <HealthCard title="Approval rule" to="/approval-rules" query={approvals}>
          {activeApproval ? (
            <>
              <Row k="Approver roles" v={activeApproval.approverRoles.join(', ')} />
              <Row
                k="Quorum"
                v={`${activeApproval.requiredApprovals} approval${activeApproval.requiredApprovals === 1 ? '' : 's'}`}
              />
              <Row k="Separation of duties" v={activeApproval.separationOfDuties ? 'enforced' : 'off'} />
              <Row k="TTL" v={`${activeApproval.ttlHours} h`} />
            </>
          ) : (
            <p className="text-danger">No ACTIVE approval configuration.</p>
          )}
        </HealthCard>

        <HealthCard title="Scoped exceptions" to="/exceptions" query={exceptions}>
          {counts && (
            <>
              <Row k="Active" v={counts.active} />
              <Row
                k="Expiring within 7 days"
                v={
                  counts.expiring > 0 ? (
                    <Link to="/exceptions?status=expiring" className="text-warn hover:underline">
                      <Badge tone="warn">{counts.expiring}</Badge>
                    </Link>
                  ) : (
                    0
                  )
                }
              />
            </>
          )}
        </HealthCard>

        <HealthCard title="Dead letters" to="/audit#dead-letters" query={deadLetters}>
          <Row
            k="Open"
            v={
              openDeadLetters.length > 0 ? (
                <Badge tone="danger">{openDeadLetters.length}</Badge>
              ) : (
                <Badge tone="ok">0</Badge>
              )
            }
          />
          {openDeadLetters.length > 0 && (
            <ul className="mt-2 divide-y divide-border text-xs">
              {openDeadLetters.slice(0, 5).map((d) => (
                <li key={d.id} className="flex items-center justify-between gap-2 py-1">
                  <span className="truncate" title={d.error ?? d.reason}>
                    #{d.id} {d.topic}@{d.partition}:{d.offset} — {d.reason}
                  </span>
                  <AdminOnly>
                    <Button
                      size="sm"
                      variant="secondary"
                      loading={resolve.isPending && resolve.variables === d.id}
                      onClick={() =>
                        resolve.mutate(d.id, {
                          onSuccess: () => toast.success(`Dead letter #${d.id} resolved`),
                          onError: (e) => toast.error(describeError(e)),
                        })
                      }
                    >
                      Resolve
                    </Button>
                  </AdminOnly>
                </li>
              ))}
            </ul>
          )}
        </HealthCard>

        <HealthCard title="Recent audit" to="/audit" query={audit}>
          {audit.data && audit.data.length === 0 ? (
            <p className="text-muted">No audit entries yet.</p>
          ) : (
            <ul className="divide-y divide-border text-xs">
              {audit.data?.slice(0, 10).map((a) => (
                <li key={a.id} className="flex flex-wrap items-baseline gap-x-2 py-1">
                  <span className="text-muted" title={formatInstant(a.occurredAt)}>
                    {relative(a.occurredAt)}
                  </span>
                  <span className="font-medium">{a.actorId}</span>
                  <span>{a.action}</span>
                  <span className="mono text-muted" title={a.targetId}>
                    {a.targetType}/{shortId(a.targetId)}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </HealthCard>
      </div>
    </div>
  );
}
