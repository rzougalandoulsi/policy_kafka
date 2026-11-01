import { ACTIVATION_GRACE_MS, describeState, initialState, nextState } from './activation';
import { bundles } from '@/test/fixtures';

const target = 'v3';

describe('activation state machine', () => {
  it('waits while OPA still reports the old revision', () => {
    const s = nextState(
      initialState(target),
      { activeRevision: 'v3', opaRevision: 'v2', match: false },
      [],
      5_000,
    );
    expect(s.phase).toBe('waiting');
    expect(s.opaRevision).toBe('v2');
    expect(describeState(s)).toContain('5 s elapsed');
  });

  it('reports adoption when diagnostics match the target', () => {
    const s = nextState(
      initialState(target),
      { activeRevision: 'v3', opaRevision: 'v3', match: true },
      [],
      12_000,
    );
    expect(s.phase).toBe('adopted');
    expect(describeState(s)).toContain('adopted revision v3');
  });

  it('does not treat a match on a different revision as adoption', () => {
    const s = nextState(
      initialState(target),
      { activeRevision: 'v2', opaRevision: 'v2', match: true },
      [],
      1_000,
    );
    expect(s.phase).toBe('waiting');
  });

  it('reports mismatch once the grace period has elapsed', () => {
    const s = nextState(
      initialState(target),
      { activeRevision: 'v3', opaRevision: 'v2', match: false },
      [],
      ACTIVATION_GRACE_MS,
    );
    expect(s.phase).toBe('mismatch');
    expect(describeState(s)).toContain('OPA reports v2');
  });

  it('detects the automatic rollback when the target is marked FAILED', () => {
    const s = nextState(
      initialState(target),
      { activeRevision: 'v2', opaRevision: 'v2', match: true },
      bundles,
      40_000,
    );
    expect(s.phase).toBe('rolled-back');
    expect(s.failureReason).toContain('OPA did not load revision');
  });

  it('keeps terminal states', () => {
    const adopted = nextState(
      initialState(target),
      { activeRevision: 'v3', opaRevision: 'v3', match: true },
      [],
      1_000,
    );
    const later = nextState(
      adopted,
      { activeRevision: 'v3', opaRevision: 'v1', match: false },
      bundles,
      ACTIVATION_GRACE_MS * 2,
    );
    expect(later).toBe(adopted);
  });
});
