import { lifecycleOf } from './lifecycle';
import { buildExceptionSchema, toCreateBody } from './exceptionSchema';
import { toDateTimeLocal } from '@/lib/time';

const now = new Date('2026-11-01T12:00:00Z');
const base = { revokedAt: null, expiresAt: '2026-12-01T00:00:00Z' };

describe('lifecycleOf', () => {
  it('revoked wins over everything', () => {
    expect(
      lifecycleOf({ ...base, revokedAt: '2026-10-01T00:00:00Z', expiresAt: '2020-01-01T00:00:00Z' }, now),
    ).toBe('revoked');
  });
  it('expired when expiresAt is not in the future', () => {
    expect(lifecycleOf({ ...base, expiresAt: '2026-11-01T12:00:00Z' }, now)).toBe('expired');
    expect(lifecycleOf({ ...base, expiresAt: '2026-10-31T00:00:00Z' }, now)).toBe('expired');
  });
  it('expiring within seven days, otherwise active', () => {
    expect(lifecycleOf({ ...base, expiresAt: '2026-11-03T00:00:00Z' }, now)).toBe('expiring');
    expect(lifecycleOf({ ...base, expiresAt: '2026-11-08T12:00:00Z' }, now)).toBe('expiring');
    expect(lifecycleOf({ ...base, expiresAt: '2026-11-08T12:00:01Z' }, now)).toBe('active');
  });
});

describe('exceptionSchema (parity with ExceptionAdminService.create)', () => {
  const schema = buildExceptionSchema(() => now);
  const valid = {
    policyId: 'MAX_PARTITIONS_PER_TOPIC',
    teamId: 'team-a',
    environment: '',
    principalId: '',
    resourcePattern: '',
    actionTypes: [],
    expiresAt: toDateTimeLocal(new Date(now.getTime() + 24 * 3_600_000)),
    reason: 'load test',
  };
  const firstIssue = (v: typeof valid) => {
    const r = schema.safeParse(v);
    return r.success ? null : r.error.issues[0];
  };

  it('accepts a valid form', () => {
    expect(schema.safeParse(valid).success).toBe(true);
  });
  it('rejects expiry in the past', () => {
    expect(
      firstIssue({ ...valid, expiresAt: toDateTimeLocal(new Date(now.getTime() - 60_000)) })?.message,
    ).toMatch(/future/);
  });
  it('rejects expiry more than 365 days ahead', () => {
    expect(
      firstIssue({ ...valid, expiresAt: toDateTimeLocal(new Date(now.getTime() + 366 * 24 * 3_600_000)) })
        ?.message,
    ).toMatch(/365/);
  });
  it('rejects malformed policy ids', () => {
    expect(firstIssue({ ...valid, policyId: 'lower_case' })?.path).toEqual(['policyId']);
    expect(firstIssue({ ...valid, policyId: 'AB' })?.path).toEqual(['policyId']);
    expect(firstIssue({ ...valid, policyId: '' })?.message).toBe('Policy is required');
  });
  it('requires team and reason', () => {
    expect(firstIssue({ ...valid, teamId: '  ' })?.path).toEqual(['teamId']);
    expect(firstIssue({ ...valid, reason: '' })?.path).toEqual(['reason']);
  });
  it('omits blanks so the server applies its defaults', () => {
    const body = toCreateBody({ ...valid, actionTypes: ['TOPIC_DELETE'] });
    expect(body.environment).toBeUndefined();
    expect(body.principalId).toBeUndefined();
    expect(body.resourcePattern).toBeUndefined();
    expect(body.actionTypes).toEqual(['TOPIC_DELETE']);
    expect(body.expiresAt).toMatch(/Z$/);
  });
});
