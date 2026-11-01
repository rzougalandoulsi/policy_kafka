/** JWT payload helpers. Mirrors CallerContextResolver.stringSet: arrays, or space/comma separated strings. */
export interface Claims {
  sub?: string;
  exp?: number;
  iss?: string;
  [k: string]: unknown;
}

export function decodeJwtPayload(token: string): Claims | null {
  const parts = token.split('.');
  if (parts.length < 2) return null;
  try {
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
    const json = decodeURIComponent(
      Array.from(atob(padded), (c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''),
    );
    return JSON.parse(json) as Claims;
  } catch {
    return null;
  }
}

export function stringSet(claim: unknown): string[] {
  if (claim == null) return [];
  if (Array.isArray(claim)) return Array.from(new Set(claim.map(String)));
  if (typeof claim === 'string')
    return Array.from(
      new Set(
        claim
          .trim()
          .split(/[\s,]+/)
          .filter(Boolean),
      ),
    );
  return [String(claim)];
}

export function isExpired(claims: Claims | null, nowSeconds = Date.now() / 1000, skew = 30): boolean {
  if (!claims?.exp) return false;
  return claims.exp - skew < nowSeconds;
}
