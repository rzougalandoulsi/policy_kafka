import { ACTIVATION_GRACE_MS, describeState, initialState, nextState } from './activation';
import type { RevisionDiagnostics } from '@/api/types';
import { bundles, diagnostics } from '@/test/fixtures';

const target = { bundleName: 'core', revision: 'core-v3' };

function diag(
  coreLoaded: string,
  match: boolean,
  overrides: Partial<RevisionDiagnostics> = {},
): RevisionDiagnostics {
  return {
    ...diagnostics,
    match,
    bundles: {
      ...diagnostics.bundles,
      core: { expected: 'core-v3', loaded: coreLoaded, match: coreLoaded === 'core-v3' },
    },
    ...overrides,
  };
}

describe('activation state machine', () => {
  it('waits while OPA still reports the old revision for the bundle', () => {
    const s = nextState(initialState(target), diag('core-v2', false), [], 5_000);
    expect(s.phase).toBe('waiting');
    expect(s.opaRevision).toBe('core-v2');
    expect(describeState(s)).toContain('core revision core-v3');
    expect(describeState(s)).toContain('5 s elapsed');
  });

  it('reports adoption when the bundle matches and the whole release is in sync', () => {
    const s = nextState(initialState(target), diag('core-v3', true), [], 12_000);
    expect(s.phase).toBe('adopted');
    expect(describeState(s)).toContain('adopted core revision core-v3');
  });

  it('keeps waiting when the bundle matches but another bundle still differs', () => {
    const s = nextState(initialState(target), diag('core-v3', false), [], 1_000);
    expect(s.phase).toBe('waiting');
    expect(describeState(s)).toContain('another bundle still differs');
  });

  it('does not treat a release match on a different revision as adoption', () => {
    const d = {
      ...diagnostics,
      bundles: { ...diagnostics.bundles, core: { expected: 'core-v2', loaded: 'core-v2', match: true } },
    };
    expect(nextState(initialState(target), d, [], 1_000).phase).toBe('waiting');
  });

  it('reports mismatch once the grace period has elapsed', () => {
    const s = nextState(initialState(target), diag('core-v2', false), [], ACTIVATION_GRACE_MS);
    expect(s.phase).toBe('mismatch');
    expect(describeState(s)).toContain('OPA reports core-v2');
  });

  it('detects the automatic rollback when the target version is marked FAILED', () => {
    const s = nextState(initialState(target), diagnostics, bundles, 40_000);
    expect(s.phase).toBe('rolled-back');
    expect(s.failureReason).toContain('OPA did not load revision');
  });

  it('only looks at versions of the same bundle', () => {
    const other = { bundleName: 'naming', revision: 'core-v3' };
    expect(nextState(initialState(other), diag('core-v2', false), bundles, 1_000).phase).toBe('waiting');
  });

  it('keeps terminal states', () => {
    const adopted = nextState(initialState(target), diag('core-v3', true), [], 1_000);
    const later = nextState(adopted, diag('core-v1', false), bundles, ACTIVATION_GRACE_MS * 2);
    expect(later).toBe(adopted);
  });
});
