import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { useTeams } from '@/api/hooks';
import type { TeamConfiguration, TeamSource } from '@/api/types';
import { AdminOnly } from '@/auth/guards';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input, Label } from '@/components/ui/form';
import { PageHeader } from '@/components/ui/page';
import { Spinner } from '@/components/ui/spinner';
import { EmptyState, ErrorState } from '@/components/ui/states';
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table';
import { formatInstant } from '@/lib/time';
import { TeamEditorDialog } from './TeamEditorDialog';

export const SOURCE_TONE: Record<TeamSource, BadgeTone> = { governance: 'ok', external: 'info', default: 'neutral' };
export const SOURCE_LABEL: Record<TeamSource, string> = {
  governance: 'Governance',
  external: 'External registry',
  default: 'Defaults',
};

export function quotaSummary(t: TeamConfiguration): string {
  const entries = Object.entries(t.quotas ?? {}).filter(([, v]) => v !== undefined && v !== null);
  return entries.length ? entries.map(([k, v]) => `${k} ≤ ${v}`).join(', ') : 'unlimited';
}

export function TeamsPage() {
  const query = useTeams();
  const navigate = useNavigate();
  const [filter, setFilter] = useState('');
  const [creating, setCreating] = useState(false);
  const [lookup, setLookup] = useState('');

  const rows = useMemo(
    () =>
      (query.data ?? [])
        .filter((t) => !filter || t.teamId.toLowerCase().includes(filter.toLowerCase()))
        .sort((a, b) => a.teamId.localeCompare(b.teamId)),
    [query.data, filter],
  );

  return (
    <>
      <PageHeader
        title="Teams"
        description="Per-team governance parameters supplied to the policies: authorised topic prefixes, quotas per resource type across all repositories, allowed-role overrides. Teams without a stored configuration evaluate against the defaults (one prefix <teamId><separator>, no quotas)."
        actions={
          <AdminOnly>
            <Button onClick={() => setCreating(true)}>
              <Plus className="h-4 w-4" aria-hidden /> New team
            </Button>
          </AdminOnly>
        }
      />

      <Card className="mb-4">
        <div className="grid grid-cols-1 gap-3 px-5 py-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="f-filter">Filter</Label>
            <Input id="f-filter" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="team-a" />
          </div>
          <form
            className="flex flex-col gap-1.5"
            onSubmit={(e) => {
              e.preventDefault();
              if (lookup.trim()) navigate(`/teams/${encodeURIComponent(lookup.trim())}`);
            }}
          >
            <Label htmlFor="f-lookup">Open any team (shows its effective defaults when nothing is stored)</Label>
            <div className="flex gap-2">
              <Input id="f-lookup" value={lookup} onChange={(e) => setLookup(e.target.value)} placeholder="team-z" />
              <Button type="submit" variant="secondary">
                Open
              </Button>
            </div>
          </form>
        </div>
      </Card>

      <Card>
        {query.isPending ? (
          <Spinner label="Loading teams" className="px-5 py-8" />
        ) : query.isError ? (
          <ErrorState error={query.error} onRetry={() => void query.refetch()} />
        ) : rows.length === 0 ? (
          <EmptyState
            title="No team configuration stored"
            description="Every team evaluates against the defaults. Create a configuration to authorise extra prefixes or set quotas."
          />
        ) : (
          <Table aria-label="Teams">
            <THead>
              <TR>
                <TH>Team</TH>
                <TH>Authorised prefixes</TH>
                <TH>Quotas</TH>
                <TH>Allowed roles</TH>
                <TH>Source</TH>
                <TH>Version</TH>
                <TH>Updated</TH>
              </TR>
            </THead>
            <TBody>
              {rows.map((t) => (
                <TR key={t.teamId}>
                  <TD>
                    <Link to={`/teams/${encodeURIComponent(t.teamId)}`} className="mono text-xs font-medium text-primary hover:underline">
                      {t.teamId}
                    </Link>
                  </TD>
                  <TD>
                    <span className="flex flex-wrap gap-1">
                      {t.authorizedPrefixes.map((p) => (
                        <Badge key={p}>{p}</Badge>
                      ))}
                    </span>
                  </TD>
                  <TD className="text-xs">{quotaSummary(t)}</TD>
                  <TD className="text-xs">{t.allowedRoles.length ? t.allowedRoles.join(', ') : 'global'}</TD>
                  <TD>
                    <Badge tone={SOURCE_TONE[t.source]}>{SOURCE_LABEL[t.source]}</Badge>
                  </TD>
                  <TD>v{t.version}</TD>
                  <TD>
                    <div className="text-sm">{formatInstant(t.updatedAt)}</div>
                    <div className="text-xs text-muted">{t.updatedBy}</div>
                  </TD>
                </TR>
              ))}
            </TBody>
          </Table>
        )}
      </Card>

      <TeamEditorDialog
        open={creating}
        onOpenChange={setCreating}
        team={undefined}
        onSaved={(saved) => navigate(`/teams/${encodeURIComponent(saved.teamId)}`)}
      />
    </>
  );
}
