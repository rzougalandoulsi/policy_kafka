import { useMemo } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useCatalog, useShadowFindings } from '@/api/hooks';
import type { ShadowFinding } from '@/api/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardBody, CardHeader } from '@/components/ui/card';
import { Field, Input, Select } from '@/components/ui/form';
import { PageHeader } from '@/components/ui/page';
import { Spinner } from '@/components/ui/spinner';
import { EmptyState, ErrorState, InlineAlert } from '@/components/ui/states';
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table';
import { formatInstant, relative } from '@/lib/time';
import { shortId } from '@/lib/utils';

/** Count of findings per policy id, highest first: the evidence for flipping a policy from shadow to enforce. */
export function findingsByPolicy(findings: ShadowFinding[]): Array<{ policyId: string; count: number; requests: number }> {
  const map = new Map<string, { count: number; requests: Set<string> }>();
  for (const f of findings) {
    const e = map.get(f.policyId) ?? { count: 0, requests: new Set<string>() };
    e.count++;
    e.requests.add(f.requestId);
    map.set(f.policyId, e);
  }
  return [...map.entries()]
    .map(([policyId, e]) => ({ policyId, count: e.count, requests: e.requests.size }))
    .sort((a, b) => b.count - a.count || a.policyId.localeCompare(b.policyId));
}

/** Search params with `patch` applied; empty values clear the key. */
export function withParams(prev: URLSearchParams, patch: Record<string, string>): URLSearchParams {
  const kept = [...prev.entries()].filter(([k]) => !(k in patch));
  const added = Object.entries(patch).filter(([, v]) => !!v);
  return new URLSearchParams([...kept, ...added]);
}

export function ShadowFindingsPage() {
  const [sp, setSp] = useSearchParams();
  const policyId = sp.get('policyId') ?? '';
  const teamId = sp.get('teamId') ?? '';
  const environment = sp.get('environment') ?? '';
  const query = useShadowFindings({
    policyId: policyId || undefined,
    teamId: teamId || undefined,
    environment: environment || undefined,
    limit: 500,
  });
  const catalog = useCatalog();
  const apply = (patch: Record<string, string>) => setSp((prev) => withParams(prev, patch), { replace: true });

  const policyOptions = useMemo(() => {
    const ids = new Set<string>((catalog.data ?? []).map((p) => p.policyId));
    (query.data ?? []).forEach((f) => ids.add(f.policyId));
    return [...ids].sort();
  }, [catalog.data, query.data]);
  const summary = useMemo(() => findingsByPolicy(query.data ?? []), [query.data]);

  return (
    <>
      <PageHeader
        title="Shadow findings"
        description="What policies running in shadow mode (policy configuration → enforcement) would have denied or sent to approval. Findings are recorded with the decision and never block; flip a policy to enforce once its findings are understood."
      />

      <Card className="mb-5">
        <div className="grid grid-cols-1 gap-3 p-4 sm:grid-cols-3">
          <Field id="f-policy" label="Policy">
            <Select id="f-policy" value={policyId} onChange={(e) => apply({ policyId: e.target.value })}>
              <option value="">any</option>
              {policyOptions.map((id) => (
                <option key={id} value={id}>
                  {id}
                </option>
              ))}
            </Select>
          </Field>
          <Field id="f-team" label="Team">
            <Input id="f-team" defaultValue={teamId} onBlur={(e) => apply({ teamId: e.target.value.trim() })} placeholder="team-a" />
          </Field>
          <Field id="f-env" label="Environment">
            <Input id="f-env" defaultValue={environment} onBlur={(e) => apply({ environment: e.target.value.trim() })} placeholder="production" />
          </Field>
        </div>
        {sp.toString() !== '' && (
          <div className="border-t border-border px-4 py-2">
            <Button variant="link" size="sm" onClick={() => setSp(new URLSearchParams(), { replace: true })}>
              Reset filters
            </Button>
          </div>
        )}
      </Card>

      {query.isPending ? (
        <Spinner label="Loading shadow findings" />
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : (
        <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
          <Card className="self-start">
            <CardHeader title="By policy" description="Within the shown findings." />
            <CardBody>
              {summary.length === 0 ? (
                <p className="text-sm text-muted">Nothing to summarise.</p>
              ) : (
                <ul className="flex flex-col gap-2">
                  {summary.map((s) => (
                    <li key={s.policyId} className="flex items-center justify-between gap-2 text-sm">
                      <button type="button" className="mono text-xs text-primary hover:underline" onClick={() => apply({ policyId: s.policyId })}>
                        {s.policyId}
                      </button>
                      <span className="text-xs text-muted">
                        {s.count} finding{s.count === 1 ? '' : 's'} · {s.requests} request{s.requests === 1 ? '' : 's'}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
              <div className="mt-4">
                <InlineAlert tone="info">
                  To change a policy&apos;s mode, create a new{' '}
                  <Link to="/policy-configurations/new" className="underline">
                    policy configuration version
                  </Link>{' '}
                  and edit its enforcement section.
                </InlineAlert>
              </div>
            </CardBody>
          </Card>
          <Card>
            <CardHeader title="Findings" description={`${query.data.length} shown, newest first (limit 500).`} />
            {query.data.length === 0 ? (
              <EmptyState title="No shadow findings" description="Either no policy runs in shadow mode, or none of them matched." />
            ) : (
              <Table aria-label="Shadow findings">
                <THead>
                  <TR>
                    <TH>Recorded</TH>
                    <TH>Policy</TH>
                    <TH>Kind</TH>
                    <TH>Resource</TH>
                    <TH>Team / repository</TH>
                    <TH>Stage</TH>
                    <TH>Request</TH>
                    <TH>Message</TH>
                  </TR>
                </THead>
                <TBody>
                  {query.data.map((f) => (
                    <TR key={f.id}>
                      <TD>
                        <div className="whitespace-nowrap">{formatInstant(f.recordedAt)}</div>
                        <div className="text-xs text-muted">{relative(f.recordedAt)}</div>
                      </TD>
                      <TD className="mono text-xs">{f.policyId}</TD>
                      <TD>
                        <Badge tone={f.kind === 'APPROVAL' ? 'info' : f.severity === 'HARD_DENY' ? 'danger' : 'warn'}>
                          {f.kind === 'APPROVAL' ? 'would need approval' : `would ${f.severity === 'HARD_DENY' ? 'hard deny' : 'deny'}`}
                        </Badge>
                      </TD>
                      <TD className="mono text-xs">
                        {f.resourceType ? `${f.resourceType} ` : ''}
                        {f.resourceName}
                      </TD>
                      <TD className="text-xs">
                        <Link to={`/teams/${encodeURIComponent(f.teamId)}`} className="text-primary hover:underline">
                          {f.teamId}
                        </Link>{' '}
                        / {f.repositoryId} · {f.environment}
                      </TD>
                      <TD className="text-xs">{f.stage}</TD>
                      <TD className="mono text-xs" title={f.requestId}>
                        {shortId(f.requestId)}
                      </TD>
                      <TD className="max-w-md text-xs text-muted">{f.message}</TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            )}
          </Card>
        </div>
      )}
    </>
  );
}
