import { diffJson, type DiffEntry } from '@/lib/json-diff';
import { stableStringify } from '@/lib/utils';
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table';
import { Badge, type BadgeTone } from '@/components/ui/badge';

const TONE: Record<DiffEntry['kind'], BadgeTone> = { added: 'ok', removed: 'danger', changed: 'warn' };
const ROW: Record<DiffEntry['kind'], string> = {
  added: 'bg-ok-bg/40',
  removed: 'bg-danger-bg/40',
  changed: 'bg-warn-bg/40',
};

function Value({ value, present }: { value: unknown; present: boolean }) {
  if (!present) return <span className="text-muted">—</span>;
  const text = value === undefined ? 'undefined' : stableStringify(value, 0);
  const long = text.length > 80;
  return (
    <code className="mono block max-w-xs truncate text-xs" title={long ? stableStringify(value) : undefined}>
      {long ? text.slice(0, 80) + '…' : text}
    </code>
  );
}

/** Structural diff of two configuration documents (before = currently ACTIVE, after = candidate). */
export function ConfigDiff({
  before,
  after,
  beforeLabel = 'Before',
  afterLabel = 'After',
}: {
  before: unknown;
  after: unknown;
  beforeLabel?: string;
  afterLabel?: string;
}) {
  const entries = diffJson(before, after);
  if (entries.length === 0) return <p className="text-sm text-muted">No differences.</p>;
  return (
    <Table aria-label="Configuration differences">
      <THead>
        <TR>
          <TH>Path</TH>
          <TH>Change</TH>
          <TH>{beforeLabel}</TH>
          <TH>{afterLabel}</TH>
        </TR>
      </THead>
      <TBody>
        {entries.map((e) => (
          <TR key={e.path} className={ROW[e.kind]} data-kind={e.kind}>
            <TD className="mono text-xs">{e.path}</TD>
            <TD>
              <Badge tone={TONE[e.kind]}>{e.kind}</Badge>
            </TD>
            <TD>
              <Value value={e.before} present={e.kind !== 'added'} />
            </TD>
            <TD>
              <Value value={e.after} present={e.kind !== 'removed'} />
            </TD>
          </TR>
        ))}
      </TBody>
    </Table>
  );
}
