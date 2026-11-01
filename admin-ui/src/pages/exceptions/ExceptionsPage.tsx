import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { useCatalog, useExceptions } from '@/api/hooks';
import { AdminOnly } from '@/auth/guards';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input, Label, Select } from '@/components/ui/form';
import { PageHeader } from '@/components/ui/page';
import { Spinner } from '@/components/ui/spinner';
import { EmptyState, ErrorState } from '@/components/ui/states';
import { Switch } from '@/components/ui/switch';
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table';
import { formatInstant, relative } from '@/lib/time';
import { CreateExceptionDrawer } from './CreateExceptionDrawer';
import { LIFECYCLE_LABEL, LIFECYCLE_TONE, lifecycleOf, type ExceptionLifecycle } from './lifecycle';

type StatusFilter = 'all' | ExceptionLifecycle;

const STATUS_FILTERS: StatusFilter[] = ['all', 'active', 'expiring', 'expired', 'revoked'];

export function ExceptionsPage() {
  // `?status=expiring` is linked from the overview; expired/revoked filters only make sense with inactive rows.
  const [searchParams, setSearchParams] = useSearchParams();
  const initialStatus = searchParams.get('status') as StatusFilter | null;
  const [teamInput, setTeamInput] = useState(searchParams.get('teamId') ?? '');
  const [teamId, setTeamId] = useState(searchParams.get('teamId') ?? '');
  const [includeInactive, setIncludeInactive] = useState(
    initialStatus === 'expired' || initialStatus === 'revoked',
  );
  const [policyId, setPolicyId] = useState(searchParams.get('policyId') ?? '');
  const [status, setStatusState] = useState<StatusFilter>(
    initialStatus && STATUS_FILTERS.includes(initialStatus) ? initialStatus : 'all',
  );
  const setStatus = (next: StatusFilter) => {
    setStatusState(next);
    if (next === 'expired' || next === 'revoked') setIncludeInactive(true);
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        if (next === 'all') p.delete('status');
        else p.set('status', next);
        return p;
      },
      { replace: true },
    );
  };
  const [drawerOpen, setDrawerOpen] = useState(false);

  const query = useExceptions({ teamId: teamId || undefined, includeInactive });
  const catalog = useCatalog();

  const rows = useMemo(() => {
    const now = new Date();
    return (query.data ?? [])
      .map((e) => ({ e, lifecycle: lifecycleOf(e, now) }))
      .filter(
        ({ e, lifecycle }) =>
          (!policyId || e.policyId === policyId) && (status === 'all' || lifecycle === status),
      )
      .sort((a, b) => new Date(a.e.expiresAt).getTime() - new Date(b.e.expiresAt).getTime());
  }, [query.data, policyId, status]);

  const policyOptions = useMemo(() => {
    const ids = new Set<string>((catalog.data ?? []).map((p) => p.policyId));
    (query.data ?? []).forEach((e) => ids.add(e.policyId));
    return Array.from(ids).sort();
  }, [catalog.data, query.data]);

  return (
    <>
      <PageHeader
        title="Scoped exceptions"
        description="Time-boxed exemptions from bypassable policies, scoped to a team, environment, principal, resource pattern and action types."
        actions={
          <AdminOnly>
            <Button onClick={() => setDrawerOpen(true)}>
              <Plus className="h-4 w-4" aria-hidden /> New exception
            </Button>
          </AdminOnly>
        }
      />

      <Card className="mb-4">
        <form
          className="grid grid-cols-1 gap-3 px-5 py-4 sm:grid-cols-2 lg:grid-cols-4"
          onSubmit={(e) => {
            e.preventDefault();
            setTeamId(teamInput.trim());
          }}
        >
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="f-team">Team</Label>
            <div className="flex gap-2">
              <Input
                id="f-team"
                value={teamInput}
                onChange={(e) => setTeamInput(e.target.value)}
                placeholder="team-a"
              />
              <Button type="submit" variant="secondary" size="md">
                Apply
              </Button>
            </div>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="f-policy">Policy</Label>
            <Select id="f-policy" value={policyId} onChange={(e) => setPolicyId(e.target.value)}>
              <option value="">All policies</option>
              {policyOptions.map((id) => (
                <option key={id} value={id}>
                  {id}
                </option>
              ))}
            </Select>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="f-status">Status</Label>
            <Select id="f-status" value={status} onChange={(e) => setStatus(e.target.value as StatusFilter)}>
              <option value="all">All</option>
              <option value="active">Active</option>
              <option value="expiring">Expiring soon</option>
              <option value="expired">Expired</option>
              <option value="revoked">Revoked</option>
            </Select>
          </div>
          <div className="flex items-center gap-2 pt-6">
            <Switch id="f-inactive" checked={includeInactive} onCheckedChange={setIncludeInactive} />
            <Label htmlFor="f-inactive">Include expired and revoked</Label>
          </div>
        </form>
      </Card>

      <Card>
        {query.isLoading ? (
          <Spinner label="Loading exceptions" className="px-5 py-8" />
        ) : query.isError ? (
          <ErrorState error={query.error} onRetry={() => query.refetch()} />
        ) : rows.length === 0 ? (
          <EmptyState
            title="No exceptions match"
            description={
              includeInactive
                ? 'Adjust the filters, or create a new scoped exception.'
                : 'Only active exceptions are shown. Turn on "Include expired and revoked" to see history.'
            }
          />
        ) : (
          <Table>
            <THead>
              <TR>
                <TH>Policy</TH>
                <TH>Scope</TH>
                <TH>Resource pattern</TH>
                <TH>Actions</TH>
                <TH>Expires</TH>
                <TH>Status</TH>
                <TH>Created by</TH>
              </TR>
            </THead>
            <TBody>
              {rows.map(({ e, lifecycle }) => (
                <TR key={e.id}>
                  <TD>
                    <Link
                      to={`/exceptions/${encodeURIComponent(e.id)}`}
                      className="mono text-xs font-medium text-primary hover:underline"
                    >
                      {e.policyId}
                    </Link>
                  </TD>
                  <TD>
                    <div className="text-sm">{e.teamId}</div>
                    <div className="text-xs text-muted">
                      {e.environment === '*' ? 'any environment' : e.environment} ·{' '}
                      {e.principalId ?? 'any principal'}
                    </div>
                  </TD>
                  <TD className="mono text-xs">{e.resourcePattern}</TD>
                  <TD className="text-xs">{e.actionTypes.length ? e.actionTypes.join(', ') : 'any'}</TD>
                  <TD>
                    <div className="text-sm">{formatInstant(e.expiresAt)}</div>
                    <div className="text-xs text-muted">{relative(e.expiresAt)}</div>
                  </TD>
                  <TD>
                    <Badge tone={LIFECYCLE_TONE[lifecycle]}>{LIFECYCLE_LABEL[lifecycle]}</Badge>
                  </TD>
                  <TD className="text-xs">{e.createdBy}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        )}
      </Card>

      <CreateExceptionDrawer open={drawerOpen} onOpenChange={setDrawerOpen} defaultTeamId={teamId} />
    </>
  );
}
