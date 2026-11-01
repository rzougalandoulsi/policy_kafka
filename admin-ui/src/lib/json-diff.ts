/** Structural diff of two JSON documents as a flat list of changed paths. */
export type DiffKind = 'added' | 'removed' | 'changed';
export interface DiffEntry {
  path: string;
  kind: DiffKind;
  before?: unknown;
  after?: unknown;
}

export function diffJson(before: unknown, after: unknown, path = '$'): DiffEntry[] {
  if (deepEqual(before, after)) return [];
  const bothObjects = isPlainObject(before) && isPlainObject(after);
  if (bothObjects) {
    const keys = new Set([...Object.keys(before), ...Object.keys(after)]);
    const out: DiffEntry[] = [];
    for (const k of Array.from(keys).sort()) {
      const p = `${path}.${k}`;
      if (!(k in before)) out.push({ path: p, kind: 'added', after: after[k] });
      else if (!(k in after)) out.push({ path: p, kind: 'removed', before: before[k] });
      else out.push(...diffJson(before[k], after[k], p));
    }
    return out;
  }
  if (Array.isArray(before) && Array.isArray(after) && before.every(isScalar) && after.every(isScalar)) {
    return [{ path, kind: 'changed', before, after }];
  }
  if (Array.isArray(before) && Array.isArray(after)) {
    const out: DiffEntry[] = [];
    const n = Math.max(before.length, after.length);
    for (let i = 0; i < n; i++) {
      const p = `${path}[${i}]`;
      if (i >= before.length) out.push({ path: p, kind: 'added', after: after[i] });
      else if (i >= after.length) out.push({ path: p, kind: 'removed', before: before[i] });
      else out.push(...diffJson(before[i], after[i], p));
    }
    return out;
  }
  return [{ path, kind: 'changed', before, after }];
}

export function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a !== typeof b || a === null || b === null) return false;
  if (Array.isArray(a)) {
    if (!Array.isArray(b) || a.length !== b.length) return false;
    return a.every((v, i) => deepEqual(v, b[i]));
  }
  if (isPlainObject(a) && isPlainObject(b)) {
    const ka = Object.keys(a);
    const kb = Object.keys(b);
    if (ka.length !== kb.length) return false;
    return ka.every((k) => k in b && deepEqual(a[k], b[k]));
  }
  return false;
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}
function isScalar(v: unknown): boolean {
  return v === null || ['string', 'number', 'boolean'].includes(typeof v);
}
