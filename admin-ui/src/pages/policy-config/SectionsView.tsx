import { Card, CardBody, CardHeader } from '@/components/ui/card';
import { KV } from '@/components/ui/kv';
import { Badge } from '@/components/ui/badge';
import { stableStringify } from '@/lib/utils';
import type { PolicyConfigurationContent } from '@/api/types';
import { REQUIRED_SECTIONS } from './configSchema';

function render(v: unknown) {
  if (typeof v === 'boolean') return <Badge tone={v ? 'ok' : 'neutral'}>{v ? 'yes' : 'no'}</Badge>;
  if (typeof v === 'number' || typeof v === 'string')
    return <code className="mono text-xs">{String(v)}</code>;
  if (Array.isArray(v))
    return v.length ? (
      <span className="flex flex-wrap gap-1">
        {v.map((x, i) => (
          <Badge key={i}>{typeof x === 'object' ? stableStringify(x, 0) : String(x)}</Badge>
        ))}
      </span>
    ) : (
      <span className="text-muted">none</span>
    );
  if (v && typeof v === 'object') {
    const entries = Object.entries(v as Record<string, unknown>);
    return entries.length ? (
      <dl className="grid grid-cols-[max-content_1fr] gap-x-3 gap-y-1">
        {entries.map(([k, val]) => (
          <div key={k} className="contents">
            <dt className="mono text-xs text-muted">{k}</dt>
            <dd>{render(val)}</dd>
          </div>
        ))}
      </dl>
    ) : (
      <span className="text-muted">none</span>
    );
  }
  return <span className="text-muted">—</span>;
}

/** Read-only rendering of every section (known and unknown keys alike). */
export function SectionsView({ content }: { content: PolicyConfigurationContent | Record<string, unknown> }) {
  const known = REQUIRED_SECTIONS as readonly string[];
  const sections = [
    ...known.filter((s) => s in content),
    ...Object.keys(content).filter((k) => !known.includes(k)),
  ];
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      {sections.map((s) => {
        const value = (content as Record<string, unknown>)[s];
        const entries =
          value && typeof value === 'object' && !Array.isArray(value)
            ? Object.entries(value as Record<string, unknown>)
            : null;
        return (
          <Card key={s}>
            <CardHeader title={<span className="mono">{s}</span>} />
            <CardBody>
              {entries ? (
                <KV
                  items={entries.map(([k, v]) => ({
                    k: <span className="mono text-xs">{k}</span>,
                    v: render(v),
                  }))}
                />
              ) : (
                render(value)
              )}
            </CardBody>
          </Card>
        );
      })}
    </div>
  );
}
