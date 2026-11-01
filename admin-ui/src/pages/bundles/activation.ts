import type { PolicyBundleVersion, RevisionDiagnostics } from '@/api/types';

/** Mirrors PolicyBundleService.ACTIVATION_GRACE: OPA must adopt a revision within 90 s or it is marked FAILED. */
export const ACTIVATION_GRACE_MS = 90_000;
export const POLL_INTERVAL_MS = 3_000;

export type ActivationPhase = 'waiting' | 'adopted' | 'mismatch' | 'rolled-back';

/** The version one bundle should converge to. */
export interface ActivationTarget {
  bundleName: string;
  revision: string;
}

export interface ActivationState {
  phase: ActivationPhase;
  bundleName: string;
  targetRevision: string;
  elapsedMs: number;
  /** revision OPA reports for this bundle */
  opaRevision: string | null;
  /** whether the whole release (all six bundles) currently matches */
  releaseMatch: boolean | null;
  failureReason: string | null;
}

export function initialState(target: ActivationTarget): ActivationState {
  return {
    phase: 'waiting',
    bundleName: target.bundleName,
    targetRevision: target.revision,
    elapsedMs: 0,
    opaRevision: null,
    releaseMatch: null,
    failureReason: null,
  };
}

export function isTerminal(phase: ActivationPhase): boolean {
  return phase !== 'waiting';
}

/**
 * Pure transition: given the latest diagnostics and bundle list, decide what the watcher should report.
 * Terminal phases never change. Precedence: auto-rollback (server marked the target FAILED) > adopted
 * (OPA runs the target revision for this bundle and the whole release matches) > mismatch after the grace
 * period > still waiting.
 */
export function nextState(
  prev: ActivationState,
  diagnostics: RevisionDiagnostics | undefined,
  bundles: PolicyBundleVersion[] | undefined,
  elapsedMs: number,
  target: ActivationTarget = { bundleName: prev.bundleName, revision: prev.targetRevision },
): ActivationState {
  if (isTerminal(prev.phase)) return prev;
  const mine = diagnostics?.bundles[target.bundleName];
  const base: ActivationState = {
    ...prev,
    bundleName: target.bundleName,
    targetRevision: target.revision,
    elapsedMs,
    opaRevision: mine?.loaded ?? prev.opaRevision,
    releaseMatch: diagnostics ? diagnostics.match : prev.releaseMatch,
  };
  const version = bundles?.find((b) => b.bundleName === target.bundleName && b.revision === target.revision);
  if (version?.status === 'FAILED') {
    return { ...base, phase: 'rolled-back', failureReason: version.failureReason };
  }
  if (diagnostics && diagnostics.match && mine?.match && mine.loaded === target.revision) {
    return { ...base, phase: 'adopted' };
  }
  if (elapsedMs >= ACTIVATION_GRACE_MS) {
    return { ...base, phase: 'mismatch' };
  }
  return { ...base, phase: 'waiting' };
}

export function describeState(s: ActivationState): string {
  const secs = Math.floor(s.elapsedMs / 1000);
  const what = `${s.bundleName} revision ${s.targetRevision}`;
  switch (s.phase) {
    case 'adopted':
      return `OPA adopted ${what} after ${secs} s; the release is in sync.`;
    case 'rolled-back':
      return `Activation of ${what} failed and the previous version was restored automatically${
        s.failureReason ? `: ${s.failureReason}` : '.'
      }`;
    case 'mismatch':
      return `OPA has not adopted ${what} after ${secs} s (OPA reports ${s.opaRevision ?? 'nothing'}). Governance will mark it FAILED and restore the previous version.`;
    default:
      return `Waiting for OPA to adopt ${what}… ${secs} s elapsed (OPA reports ${s.opaRevision ?? 'nothing'} so far${
        s.releaseMatch === false && s.opaRevision === s.targetRevision ? '; another bundle still differs' : ''
      }).`;
  }
}
