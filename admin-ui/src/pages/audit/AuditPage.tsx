import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { toast } from 'sonner';
import { describeError } from '@/api/client';
import { useAudit, useDeadLetters, useResolveDeadLetter } from '@/api/hooks';
import { AUDIT_TARGET_TYPES, type AuditEntry, type DeadLetter } from '@/api/types';
import { AdminOnly } from '@/auth/guards';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardHeader } from '@/components/ui/card';
import { ConfirmDialog } from '@/components/ui/confirm-dialog';
import { Field, Input, Select } from '@/components/ui/form';
import { JsonView } from '@/components/ui/json-view';
import { PageHeader } from '@/components/ui/page';
import { Spinner } from '@/components/ui/spinner';
import { EmptyState, ErrorState } from '@/components/ui/states';
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table';
import { formatInstant, fromDateTimeLocal, parseInstant, relative, toDateTimeLocal } from '@/lib/time';
import { shortId } from '@/lib/utils';
import {
  actionTone,
  filtersFromSearch,
  LIMIT_OPTIONS,
  searchFromFilters,
  targetLink,
  toQuery,
  type AuditFilters,
} from './auditFilters';

const DEBOUNCE_MS = 300;

export function AuditPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => filtersFromSearch(searchParams), [searchParams]);
  const query = useAudit(toQuery(filters));

  const apply = (patch: Partial<AuditFilters>) =>
    setSearchParams(searchFromFilters({ ...filters, ...patch }), { replace: true });

  return (
    <>
      <PageHeader
        title="Audit"
        description="Every governance-relevant change, recorded inside the transaction that made it. Filters live in the URL, so a filtered view can be shared."
      />

      <Card className="mb-5">
        <div className="grid grid-cols-1 gap-3 p-4 sm:grid-cols-2 lg:grid-cols-6">
          <Field id="f-targetType" label="Target type">
            <Select
              id="f-targetType"
              value={filters.targetType}
              onChange={(e) => apply({ targetType: e.target.value })}
            >
              <option value="">any</option>
              {AUDIT_TARGET_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </Select>
          </Field>
          <DebouncedText
            id="f-targetId"
            label="Target id"
            value={filters.targetId}
            onCommit={(v) => apply({ targetId: v })}
          />
          <DebouncedText
            id="f-requestId"
            label="Request id"
            value={filters.requestId}
            onCommit={(v) => apply({ requestId: v })}
          />
          <DebouncedText
            id="f-actor"
            label="Actor"
            value={filters.actor}
            onCommit={(v) => apply({ actor: v })}
          />
          <Field id="f-since" label="Since">
            <Input
              id="f-since"
              type="datetime-local"
              value={filters.since ? toDateTimeLocal(parseInstant(filters.since) ?? new Date()) : ''}
              onChange={(e) => {
                const d = fromDateTimeLocal(e.target.value);
                apply({ since: d ? d.toISOString() : '' });
              }}
            />
          </Field>
          <Field id="f-limit" label="Limit">
            <Select
              id="f-limit"
              value={filters.limit}
              onChange={(e) => apply({ limit: Number(e.target.value) })}
            >
              {LIMIT_OPTIONS.map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        {searchParams.toString() !== '' && (
          <div className="border-t border-border px-4 py-2">
            <Button
              variant="link"
              size="sm"
              onClick={() => setSearchParams(new URLSearchParams(), { replace: true })}
            >
              Clear filters
            </Button>
          </div>
        )}
      </Card>

      <Card className="mb-8">
        <CardHeader
          title="Entries"
          description={query.data ? `${query.data.length} shown (limit ${filters.limit})` : undefined}
        />
        {query.isPending ? (
          <Spinner label="Loading audit entries" className="p-4" />
        ) : query.isError ? (
          <ErrorState error={query.error} onRetry={() => void query.refetch()} />
        ) : query.data.length === 0 ? (
          <EmptyState
            title="No audit entries match"
            description="Widen the filters or check the time range."
          />
        ) : (
          <AuditTable entries={query.data} />
        )}
      </Card>

      <DeadLettersSection />
    </>
  );
}

function DebouncedText({
  id,
  label,
  value,
  onCommit,
}: {
  id: string;
  label: string;
  value: string;
  onCommit: (v: string) => void;
}) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  useEffect(() => {
    if (draft === value) return;
    const t = setTimeout(() => onCommit(draft.trim()), DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [draft, value, onCommit]);
  return (
    <Field id={id} label={label}>
      <Input id={id} value={draft} onChange={(e) => setDraft(e.target.value)} spellCheck={false} />
    </Field>
  );
}

function AuditTable({ entries }: { entries: AuditEntry[] }) {
  const [open, setOpen] = useState<Set<number>>(new Set());
  const toggle = (id: number) =>
    setOpen((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  return (
    <Table aria-label="Audit entries">
      <THead>
        <TR>
          <TH>Time</TH>
          <TH>Actor</TH>
          <TH>Action</TH>
          <TH>Target</TH>
          <TH>Request</TH>
          <TH>Details</TH>
        </TR>
      </THead>
      <TBody>
        {entries.map((a) => {
          const link = targetLink(a.targetType, a.targetId);
          const expanded = open.has(a.id);
          const hasDetails = Object.keys(a.details ?? {}).length > 0;
          return (
            <TR key={a.id}>
              <TD>
                <div className="whitespace-nowrap">{formatInstant(a.occurredAt)}</div>
                <div className="text-xs text-muted">{relative(a.occurredAt)}</div>
              </TD>
              <TD>{a.actorId}</TD>
              <TD>
                <Badge tone={actionTone(a.action)}>{a.action}</Badge>
              </TD>
              <TD>
                <div className="text-xs text-muted">{a.targetType}</div>
                {link ? (
                  <Link to={link} className="mono text-xs text-primary hover:underline" title={a.targetId}>
                    {shortId(a.targetId)}
                  </Link>
                ) : (
                  <span className="mono text-xs" title={a.targetId}>
                    {shortId(a.targetId)}
                  </span>
                )}
              </TD>
              <TD className="mono text-xs">
                {a.requestId ? <span title={a.requestId}>{shortId(a.requestId)}</span> : '—'}
              </TD>
              <TD className="min-w-56">
                {hasDetails ? (
                  <>
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-expanded={expanded}
                      aria-controls={`audit-details-${a.id}`}
                      onClick={() => toggle(a.id)}
                    >
                      {expanded ? (
                        <ChevronDown className="h-4 w-4" aria-hidden />
                      ) : (
                        <ChevronRight className="h-4 w-4" aria-hidden />
                      )}
                      {expanded ? 'Hide details' : 'Show details'}
                    </Button>
                    {expanded && (
                      <div id={`audit-details-${a.id}`} className="mt-2">
                        <JsonView value={a.details} maxHeight="16rem" />
                      </div>
                    )}
                  </>
                ) : (
                  <span className="text-xs text-muted">—</span>
                )}
              </TD>
            </TR>
          );
        })}
      </TBody>
    </Table>
  );
}

function DeadLettersSection() {
  const query = useDeadLetters();
  const resolve = useResolveDeadLetter();
  const [target, setTarget] = useState<DeadLetter | null>(null);
  return (
    <Card id="dead-letters">
      <CardHeader
        title="Dead letters"
        description="Kafka events Governance could not process (poison records or exhausted retries). Resolve one only after the cause is fixed."
      />
      {query.isPending ? (
        <Spinner label="Loading dead letters" className="p-4" />
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : query.data.length === 0 ? (
        <EmptyState title="No open dead letters" description="Every consumed event was handled." />
      ) : (
        <Table aria-label="Dead letters">
          <THead>
            <TR>
              <TH>Id</TH>
              <TH>Recorded</TH>
              <TH>Topic / partition / offset</TH>
              <TH>Event</TH>
              <TH>Request</TH>
              <TH>Reason</TH>
              <TH className="text-right">Actions</TH>
            </TR>
          </THead>
          <TBody>
            {query.data.map((d) => (
              <TR key={d.id}>
                <TD>{d.id}</TD>
                <TD>
                  <div className="whitespace-nowrap">{formatInstant(d.recordedAt)}</div>
                  <div className="text-xs text-muted">{relative(d.recordedAt)}</div>
                </TD>
                <TD className="mono text-xs">
                  {d.topic} / {d.partition} / {d.offset}
                </TD>
                <TD className="mono text-xs">{d.eventId ?? '—'}</TD>
                <TD className="mono text-xs">{d.requestId ?? '—'}</TD>
                <TD>
                  <div>{d.reason}</div>
                  {d.error && <div className="mt-1 max-w-md break-words text-xs text-muted">{d.error}</div>}
                </TD>
                <TD className="text-right">
                  <AdminOnly>
                    <Button size="sm" variant="secondary" onClick={() => setTarget(d)}>
                      Resolve
                    </Button>
                  </AdminOnly>
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      )}
      <ConfirmDialog
        open={target !== null}
        onOpenChange={(o) => !o && setTarget(null)}
        title={target ? `Resolve dead letter ${target.id}?` : ''}
        description="Marks the record as handled. The event is not replayed: make sure the underlying problem has been fixed or the request resubmitted."
        confirmLabel="Resolve"
        tone="danger"
        onConfirm={async () => {
          if (!target) return;
          try {
            await resolve.mutateAsync(target.id);
            toast.success(`Dead letter ${target.id} resolved`);
          } catch (e) {
            toast.error(describeError(e));
            throw e;
          }
        }}
      />
    </Card>
  );
}
