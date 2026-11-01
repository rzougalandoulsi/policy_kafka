import { entitlements, sessionFromToken } from './session';
import { decodeJwtPayload, isExpired, stringSet } from './claims';
import { ADMIN_TOKEN, AUDITOR_TOKEN, DEV_TOKEN, fakeJwt } from '@/test/fixtures';
import { testConfig } from '@/test/render';

describe('claims', () => {
  it('decodes base64url payloads', () => {
    expect(decodeJwtPayload(ADMIN_TOKEN)?.sub).toBe('admin-1');
    expect(decodeJwtPayload('garbage')).toBeNull();
  });
  it('normalises role claims like CallerContextResolver.stringSet', () => {
    expect(stringSet(['a', 'b', 'a'])).toEqual(['a', 'b']);
    expect(stringSet('a, b c')).toEqual(['a', 'b', 'c']);
    expect(stringSet(undefined)).toEqual([]);
  });
  it('treats tokens past exp (with skew) as expired', () => {
    const past = fakeJwt({ sub: 'x', exp: 1 });
    expect(isExpired(decodeJwtPayload(past))).toBe(true);
    expect(isExpired(decodeJwtPayload(ADMIN_TOKEN), 0)).toBe(false);
  });
});

describe('entitlements', () => {
  it('admin implies auditor', () => {
    const s = sessionFromToken(ADMIN_TOKEN, 'token', testConfig);
    expect(entitlements(s, testConfig)).toEqual({
      isAdmin: true,
      isAuditor: true,
      canRead: true,
      canWrite: true,
    });
  });
  it('auditor is read-only', () => {
    const s = sessionFromToken(AUDITOR_TOKEN, 'token', testConfig);
    expect(entitlements(s, testConfig)).toMatchObject({ isAdmin: false, canRead: true, canWrite: false });
  });
  it('plain developers have no access', () => {
    const s = sessionFromToken(DEV_TOKEN, 'token', testConfig);
    expect(entitlements(s, testConfig).canRead).toBe(false);
    expect(s?.teams).toEqual(['team-a']);
  });
});
