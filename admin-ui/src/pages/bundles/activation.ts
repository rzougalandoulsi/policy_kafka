import type { PolicyBundleVersion, RevisionDiagnostics } from '@/api/types';

/** Mirrors PolicyBundleService.ACTIVATION_GRACE: OPA must adopt a revision within 90 s or it is marked FAILED. */
export const ACTIVATION_GRACE_MS = 90_000;
export const POLL_INTERVAL_MS = 3_000;

export type ActivationPhase = 'waiting' | 'adopted' | 'mismatch' | 'rolled-back';

export interface ActivationState {
  phase: ActivationPhase;
  targetRevision: string;
  elapsedMs: number;
  opaRevision: string | null;
  failureReason: string | null;
}

export function initialState(targetRevision: string): ActivationState {
  return { phase: 'waiting', targetRevision, elapsedMs: 0, opaRevision: null, failureReason: null };
}

export function isTerminal(phase: ActivationPhase): boolean {
  return phase !== 'waiting';
}

/**
 * Pure transition: given the latest diagnostics and bundle list, decide what the watcher should report.
 * Terminal phases never change. Precedence: auto-rollback (server marked the target FAILED) > adopted >
 * mismatch after the grace period > still waiting.
 */
export function nextState(
  prev: ActivationState,
  diagnostics: RevisionDiagnostics | undefined,
  bundles: PolicyBundleVersion[] | undefined,
  elapsedMs: number,
  targetRevision: string = prev.targetRevision,
): ActivationState {
  if (isTerminal(prev.phase)) return prev;
  const base: ActivationState = {
    ...prev,
    targetRevision,
    elapsedMs,
    opaRevision: diagnostics?.opaRevision ?? prev.opaRevision,
  };
  const target = bundles?.find((b) => b.revision === targetRevision);
  if (target?.status === 'FAILED') {
    return { ...base, phase: 'rolled-back', failureReason: target.failureReason };
  }
  if (diagnostics && diagnostics.match && diagnostics.activeRevision === targetRevision) {
    return { ...base, phase: 'adopted' };
  }
  if (elapsedMs >= ACTIVATION_GRACE_MS) {
    return { ...base, phase: 'mismatch' };
  }
  return { ...base, phase: 'waiting' };
}

export function describeState(s: ActivationState): string {
  const secs = Math.floor(s.elapsedMs / 1000);
  switch (s.phase) {
    case 'adopted':
      return `OPA adopted revision ${s.targetRevision} after ${secs} s.`;
    case 'rolled-back':
      return `Activation of ${s.targetRevision} failed and the previous version was restored automatically${
        s.failureReason ? `: ${s.failureReason}` : '.'
      }`;
    case 'mismatch':
      return `OPA has not adopted ${s.targetRevision} after ${secs} s (OPA reports ${s.opaRevision ?? 'nothing'}). Governance will mark it FAILED and restore the previous version.`;
    default:
      return `Waiting for OPA to adopt revision ${s.targetRevision}… ${secs} s elapsed (OPA reports ${s.opaRevision ?? 'nothing'} so far).`;
  }
}
