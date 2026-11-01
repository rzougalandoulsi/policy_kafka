import { RefreshCw } from 'lucide-react';
import { useDiagnostics } from '@/api/hooks';
import { BUNDLE_NAMES } from '@/api/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/states';
import { describeError } from '@/api/client';

/** The policy release Governance expects versus what OPA reports, per bundle; refreshed every 15 s. */
export function DiagnosticsBanner() {
  const q = useDiagnostics({ refetchInterval: 15_000 });
  if (q.isPending) return <InlineAlert tone="info">Checking OPA bundle revisions…</InlineAlert>;
  if (q.isError)
    return <InlineAlert tone="danger">Diagnostics unavailable: {describeError(q.error)}</InlineAlert>;
  const d = q.data;
  const none = !d.releaseId;
  const missing = BUNDLE_NAMES.filter((n) => !d.bundles[n]?.expected);
  const mismatching = BUNDLE_NAMES.filter((n) => d.bundles[n]?.expected && !d.bundles[n]?.match);
  const tone = none ? 'warn' : d.match && d.ready ? 'ok' : 'danger';
  return (
    <InlineAlert tone={tone}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-3" data-testid="diagnostics">
          {none ? (
            <span>
              No policy release is active: every evaluation fails closed until all six bundles are activated.
            </span>
          ) : (
            <>
              <span>
                Release <code className="font-semibold">{d.releaseId}</code>
              </span>
              <Badge tone={d.match ? 'ok' : 'danger'}>{d.match ? 'in sync' : 'mismatch'}</Badge>
              <Badge tone={d.ready ? 'ok' : 'danger'}>{d.ready ? 'composition ready' : 'not ready'}</Badge>
              {missing.length > 0 && (
                <span>
                  no active version for <code>{missing.join(', ')}</code>
                </span>
              )}
              {mismatching.length > 0 && (
                <span>
                  OPA differs on{' '}
                  {mismatching.map((n, i) => (
                    <span key={n}>
                      {i > 0 && ', '}
                      <code>{n}</code> (expected {d.bundles[n]?.expected}, reports{' '}
                      {d.bundles[n]?.loaded ?? 'nothing'})
                    </span>
                  ))}
                </span>
              )}
            </>
          )}
        </div>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => q.refetch()}
          loading={q.isFetching}
          aria-label="Refresh diagnostics"
        >
          <RefreshCw className="h-4 w-4" aria-hidden /> Refresh
        </Button>
      </div>
    </InlineAlert>
  );
}
