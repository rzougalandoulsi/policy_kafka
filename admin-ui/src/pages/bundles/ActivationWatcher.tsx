import { useEffect, useRef, useState } from 'react';
import { useBundles, useDiagnostics } from '@/api/hooks';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/states';
import {
  describeState,
  initialState,
  isTerminal,
  nextState,
  POLL_INTERVAL_MS,
  type ActivationState,
} from './activation';

const TONE = { waiting: 'info', adopted: 'ok', mismatch: 'danger', 'rolled-back': 'danger' } as const;

/** Polls diagnostics + bundle list every 3 s for up to 90 s after an activation or rollback. */
export function ActivationWatcher({
  targetRevision,
  onDismiss,
}: {
  targetRevision: string;
  onDismiss: () => void;
}) {
  const [state, setState] = useState<ActivationState>(() => initialState(targetRevision));
  const startedAt = useRef(Date.now());
  const done = isTerminal(state.phase);
  const diagnostics = useDiagnostics({ refetchInterval: done ? false : POLL_INTERVAL_MS });
  const bundles = useBundles({ refetchInterval: done ? false : POLL_INTERVAL_MS });

  useEffect(() => {
    startedAt.current = Date.now();
    setState(initialState(targetRevision));
  }, [targetRevision]);

  useEffect(() => {
    if (done) return;
    const tick = () =>
      setState((prev) =>
        nextState(prev, diagnostics.data, bundles.data, Date.now() - startedAt.current, targetRevision),
      );
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [diagnostics.data, bundles.data, done, targetRevision]);

  return (
    <div data-testid="activation-watcher">
      <InlineAlert tone={TONE[state.phase]}>
        <div className="flex items-center justify-between gap-3">
          <span>{describeState(state)}</span>
          <Button variant="ghost" size="sm" onClick={onDismiss}>
            Dismiss
          </Button>
        </div>
      </InlineAlert>
    </div>
  );
}
