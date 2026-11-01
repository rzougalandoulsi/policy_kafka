import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { toast } from 'sonner';
import { describeError } from '@/api/client';
import { useAudit, useDeleteTeam, useTeam, useTeamHistory, useTeamUsage } from '@/api/hooks';
import { COUNTED_QUOTA_KEYS, type QuotaKey, type TeamUsage } from '@/api/types';
import { AdminOnly } from '@/auth/guards';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardBody, CardHeader } from '@/components/ui/card';
import { ConfirmDialog } from '@/components/ui/confirm-dialog';
import { JsonView } from '@/components/ui/json-view';
import { KV } from '@/components/ui/kv';
import { PageHeader } from '@/components/ui/page';
import { Spinner } from '@/components/ui/spinner';
import { EmptyState, ErrorState, InlineAlert } from '@/components/ui/states';
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { formatInstant, relative } from '@/lib/time';
import { shortId } from '@/lib/utils';
import { TeamEditorDialog } from './TeamEditorDialog';
import { SOURCE_LABEL, SOURCE_TONE } from './TeamsPage';
import { describeTeam } from './teamSchema';

/** Rows of (quota key) -> confirmed / reserved / quota / headroom for one environment. */
export function capacityRows(
  usage: TeamUsage,
  quotas: Partial<Record<QuotaKey, number>>,
  environment: string,
): Array<{ key: QuotaKey; current: number; reserved: number; quota: number | undefined; headroom: number | undefined }> {
  const current = usage.currentByEnvironment[environment] ?? {};
  const reserved = usage.reservedByEnvironment[environment] ?? {};
  const keys = new Set<QuotaKey>([...COUNTED_QUOTA_KEYS, ...(Object.keys(quotas) as QuotaKey[])]);
  return [...keys].map((key) => {
    const c = current[key] ?? 0;
    const r = reserved[key] ?? 0;
    const q = quotas[key];
    return { key, current: c, reserved: r, quota: q, headroom: q === undefined ? undefined : q - c - r };
  });
}

export function TeamDetailPage() {
  const { teamId = '' } = useParams();
  const query = useTeam(teamId, { enabled: !!teamId });
  const usage = useTeamUsage(teamId, { enabled: !!teamId, refetchInterval: 15_000 });
  const history = useTeamHistory(teamId, { enabled: !!teamId });
  const audit = useAudit({ targetType: 'team_configuration', targetId: teamId }, { enabled: !!teamId });
  const remove = useDeleteTeam();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);

  if (query.isPending) return <Spinner label="Loading team" full />;
  if (query.isError) return <ErrorState error={query.error} onRetry={() => void query.refetch()} title="Team not found" />;
  const t = query.data;
  const stored = t.source !== 'default';
  const environments = usage.data
    ? Array.from(new Set([...Object.keys(usage.data.currentByEnvironment), ...Object.keys(usage.data.reservedByEnvironment)])).sort()
    : [];

  return (
    <>
      <PageHeader
        title={
          <span className="flex flex-wrap items-center gap-2">
            <span className="mono">{t.teamId}</span>
            <Badge tone={SOURCE_TONE[t.source]}>{SOURCE_LABEL[t.source]}</Badge>
            {stored && <Badge>v{t.version}</Badge>}
          </span>
        }
        description={
          <>
            <Link to="/teams" className="text-primary hover:underline">
              Teams
            </Link>{' '}
            / {stored ? `updated ${formatInstant(t.updatedAt)} by ${t.updatedBy}` : 'no stored configuration: the defaults below apply'}
          </>
        }
        actions={
          <AdminOnly>
            <Button onClick={() => setEditing(true)}>{stored ? 'Edit' : 'Configure'}</Button>
            {stored && (
              <Button variant="danger" onClick={() => setDeleting(true)}>
                Reset to defaults
              </Button>
            )}
          </AdminOnly>
        }
      />

      {t.source === 'external' && (
        <div className="mb-4">
          <InlineAlert tone="info">
            Owned by an external registry (version {t.sourceVersion}, synced {formatInstant(t.syncedAt)}). Edits are refused
            unless ownership is taken back by saving with source &quot;governance&quot;.
          </InlineAlert>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader title="Configuration" description="What the policies receive as input.team on every evaluation." />
          <CardBody className="flex flex-col gap-4">
            <KV
              items={[
                {
                  k: 'Authorised prefixes',
                  v: (
                    <span className="flex flex-wrap gap-1">
                      {t.authorizedPrefixes.map((p) => (
                        <Badge key={p}>{p}</Badge>
                      ))}
                    </span>
                  ),
                },
                {
                  k: 'Quotas',
                  v: Object.keys(t.quotas ?? {}).length ? (
                    <span className="flex flex-wrap gap-1">
                      {Object.entries(t.quotas).map(([k, v]) => (
                        <Badge key={k} tone="info">
                          {k} ≤ {v}
                        </Badge>
                      ))}
                    </span>
                  ) : (
                    'unlimited'
                  ),
                },
                { k: 'Allowed roles', v: t.allowedRoles.length ? t.allowedRoles.join(', ') : 'global rbac configuration' },
                {
                  k: 'Environment parameters',
                  v: Object.keys(t.environments ?? {}).length ? <JsonView value={t.environments} maxHeight="10rem" /> : 'none',
                },
                { k: 'Comment', v: t.comment ?? '—' },
              ]}
            />
            <InlineAlert tone="info">{describeTeam(t)}</InlineAlert>
          </CardBody>
        </Card>

        <Card>
          <CardHeader
            title="Capacity"
            description="Confirmed usage (from the Orchestrator's confirmed state), capacity reserved by authorised plans still in flight, and the headroom left under each quota."
          />
          {usage.isPending ? (
            <Spinner label="Loading usage" className="px-5 py-6" />
          ) : usage.isError ? (
            <ErrorState error={usage.error} onRetry={() => void usage.refetch()} />
          ) : environments.length === 0 ? (
            <EmptyState title="No confirmed state yet" description="Usage appears once a manifest of this team completes." />
          ) : (
            <CardBody className="flex flex-col gap-4">
              {environments.map((env) => (
                <div key={env}>
                  <h3 className="mb-1 text-sm font-medium">{env}</h3>
                  <Table aria-label={`Capacity in ${env}`}>
                    <THead>
                      <TR>
                        <TH>Resource</TH>
                        <TH className="text-right">Confirmed</TH>
                        <TH className="text-right">Reserved</TH>
                        <TH className="text-right">Quota</TH>
                        <TH className="text-right">Headroom</TH>
                      </TR>
                    </THead>
                    <TBody>
                      {capacityRows(usage.data, t.quotas, env).map((r) => (
                        <TR key={r.key}>
                          <TD className="mono text-xs">{r.key}</TD>
                          <TD className="text-right">{r.current}</TD>
                          <TD className="text-right">{r.reserved}</TD>
                          <TD className="text-right">{r.quota ?? '∞'}</TD>
                          <TD className="text-right">
                            {r.headroom === undefined ? (
                              '—'
                            ) : (
                              <Badge tone={r.headroom < 0 ? 'danger' : r.headroom === 0 ? 'warn' : 'ok'}>{r.headroom}</Badge>
                            )}
                          </TD>
                        </TR>
                      ))}
                    </TBody>
                  </Table>
                </div>
              ))}
            </CardBody>
          )}
        </Card>
      </div>

      <Card className="mt-4">
        <Tabs defaultValue="repositories">
          <div className="px-5 pt-3">
            <TabsList>
              <TabsTrigger value="repositories">Repositories</TabsTrigger>
              <TabsTrigger value="reservations">Reservations</TabsTrigger>
              <TabsTrigger value="history">History</TabsTrigger>
              <TabsTrigger value="audit">Audit</TabsTrigger>
            </TabsList>
          </div>
          <TabsContent value="repositories">
            {usage.data && usage.data.repositories.length > 0 ? (
              <Table aria-label="Repositories">
                <THead>
                  <TR>
                    <TH>Repository</TH>
                    <TH>Environment</TH>
                    <TH>State version</TH>
                    <TH>Topics</TH>
                    <TH>Partitions</TH>
                    <TH>Pools</TH>
                    <TH>Bindings</TH>
                    <TH>Snapshot</TH>
                  </TR>
                </THead>
                <TBody>
                  {usage.data.repositories.map((r) => (
                    <TR key={`${r.environment}/${r.repositoryId}`}>
                      <TD className="mono text-xs">{r.repositoryId}</TD>
                      <TD>{r.environment}</TD>
                      <TD>{r.stateVersion < 0 ? <Badge tone="warn">none</Badge> : r.stateVersion}</TD>
                      <TD title={r.topics.join('\n')}>{r.counts.topics ?? 0}</TD>
                      <TD>{r.counts.topicPartitions ?? 0}</TD>
                      <TD title={r.identityPools.join('\n')}>{r.counts.identityPools ?? 0}</TD>
                      <TD title={r.rbacBindings.join('\n')}>{r.counts.rbacBindings ?? 0}</TD>
                      <TD className="text-xs text-muted">{r.snapshotAt ? relative(r.snapshotAt) : '—'}</TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            ) : (
              <EmptyState title="No repositories with confirmed state" />
            )}
          </TabsContent>
          <TabsContent value="reservations">
            {usage.data && usage.data.reservations.length > 0 ? (
              <Table aria-label="Reservations">
                <THead>
                  <TR>
                    <TH>Environment</TH>
                    <TH>Request</TH>
                    <TH>Plan</TH>
                    <TH>Deltas</TH>
                    <TH>Reserved</TH>
                    <TH>Expires</TH>
                  </TR>
                </THead>
                <TBody>
                  {usage.data.reservations.map((r) => (
                    <TR key={r.id}>
                      <TD>{r.environment}</TD>
                      <TD className="mono text-xs" title={r.requestId}>
                        {shortId(r.requestId)}
                      </TD>
                      <TD className="mono text-xs" title={r.planId}>
                        {shortId(r.planId)}
                      </TD>
                      <TD className="text-xs">
                        {Object.entries(r.deltas)
                          .filter(([, v]) => v)
                          .map(([k, v]) => `${k} ${v! > 0 ? '+' : ''}${v}`)
                          .join(', ')}
                      </TD>
                      <TD className="text-xs">{relative(r.createdAt)}</TD>
                      <TD className="text-xs">{relative(r.expiresAt)}</TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            ) : (
              <EmptyState title="No open reservations" description="Authorised plans hold capacity until they complete or expire." />
            )}
          </TabsContent>
          <TabsContent value="history">
            {history.isPending ? (
              <Spinner label="Loading history" className="px-5 py-6" />
            ) : history.isError ? (
              <ErrorState error={history.error} onRetry={() => void history.refetch()} />
            ) : history.data.length === 0 ? (
              <EmptyState title="No versions yet" />
            ) : (
              <Table aria-label="Configuration history">
                <THead>
                  <TR>
                    <TH>Version</TH>
                    <TH>Action</TH>
                    <TH>Changed</TH>
                    <TH>Comment</TH>
                    <TH>Content</TH>
                  </TR>
                </THead>
                <TBody>
                  {history.data.map((h) => (
                    <TR key={h.id}>
                      <TD>v{h.version}</TD>
                      <TD>
                        <Badge tone={h.action === 'DELETED' ? 'danger' : h.action === 'CREATED' ? 'ok' : 'info'}>{h.action}</Badge>
                      </TD>
                      <TD>
                        <div>{formatInstant(h.changedAt)}</div>
                        <div className="text-xs text-muted">{h.changedBy}</div>
                      </TD>
                      <TD className="text-xs">{h.comment ?? '—'}</TD>
                      <TD className="min-w-64">
                        <JsonView value={h.content} maxHeight="10rem" />
                      </TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            )}
          </TabsContent>
          <TabsContent value="audit">
            {audit.isPending ? (
              <Spinner label="Loading audit" className="px-5 py-6" />
            ) : audit.isError ? (
              <ErrorState error={audit.error} onRetry={() => void audit.refetch()} />
            ) : (audit.data ?? []).length === 0 ? (
              <EmptyState title="No audit entries" />
            ) : (
              <Table aria-label="Audit trail">
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
                      <TD className="min-w-64">
                        <JsonView value={a.details} maxHeight="10rem" />
                      </TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            )}
          </TabsContent>
        </Tabs>
      </Card>

      <TeamEditorDialog open={editing} onOpenChange={setEditing} team={stored ? t : undefined} />
      <ConfirmDialog
        open={deleting}
        onOpenChange={setDeleting}
        title={`Reset ${t.teamId} to the defaults?`}
        description="Removes the stored configuration: the team falls back to one prefix (<teamId><separator>) and no quotas on the next evaluation. The history and audit trail are kept."
        confirmLabel="Reset"
        tone="danger"
        onConfirm={async () => {
          try {
            await remove.mutateAsync(t.teamId);
            toast.success(`Team ${t.teamId} reset to the defaults`);
          } catch (e) {
            toast.error(describeError(e));
            throw e;
          }
        }}
      />
    </>
  );
}
