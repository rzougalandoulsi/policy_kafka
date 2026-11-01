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
  type ActivationTarget,
} from './activation';

const TONE = { waiting: 'info', adopted: 'ok', mismatch: 'danger', 'rolled-back': 'danger' } as const;

/** Polls diagnostics + bundle list every 3 s for up to 90 s after an activation or rollback of one bundle. */
export function ActivationWatcher({
  target,
  onDismiss,
}: {
  target: ActivationTarget;
  onDismiss: () => void;
}) {
  const [state, setState] = useState<ActivationState>(() => initialState(target));
  const startedAt = useRef(Date.now());
  const done = isTerminal(state.phase);
  const diagnostics = useDiagnostics({ refetchInterval: done ? false : POLL_INTERVAL_MS });
  const bundles = useBundles({ refetchInterval: done ? false : POLL_INTERVAL_MS });
  const { bundleName, revision } = target;

  useEffect(() => {
    startedAt.current = Date.now();
    setState(initialState({ bundleName, revision }));
  }, [bundleName, revision]);

  useEffect(() => {
    if (done) return;
    const tick = () =>
      setState((prev) =>
        nextState(prev, diagnostics.data, bundles.data, Date.now() - startedAt.current, {
          bundleName,
          revision,
        }),
      );
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [diagnostics.data, bundles.data, done, bundleName, revision]);

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
