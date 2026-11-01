import { RefreshCw } from 'lucide-react';
import { useDiagnostics } from '@/api/hooks';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/states';
import { describeError } from '@/api/client';

/** Active revision in Governance versus the revision OPA reports; refreshed every 15 s. */
export function DiagnosticsBanner() {
  const q = useDiagnostics({ refetchInterval: 15_000 });
  if (q.isPending) return <InlineAlert tone="info">Checking OPA revision…</InlineAlert>;
  if (q.isError)
    return <InlineAlert tone="danger">Diagnostics unavailable: {describeError(q.error)}</InlineAlert>;
  const d = q.data;
  const none = !d.activeRevision;
  const tone = none ? 'warn' : d.match ? 'ok' : 'danger';
  return (
    <InlineAlert tone={tone}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-3" data-testid="diagnostics">
          {none ? (
            <span>No policy bundle is active: every evaluation fails closed until one is activated.</span>
          ) : (
            <>
              <span>
                Active revision <code className="font-semibold">{d.activeRevision}</code>
              </span>
              <span>
                OPA reports <code className="font-semibold">{d.opaRevision ?? 'nothing'}</code>
              </span>
              <Badge tone={d.match ? 'ok' : 'danger'}>{d.match ? 'in sync' : 'mismatch'}</Badge>
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
