import { globMatch } from './glob';
import { diffJson } from './json-diff';
import { readTar, summarizeTestReport } from './bundle';
import { shortId, stableStringify } from './utils';

describe('glob (OPA glob.match without delimiters)', () => {
  it.each([
    ['*', 'anything', true],
    ['team-a.*', 'team-a.orders', true],
    ['team-a.*', 'team-b.orders', false],
    ['team-a.order?', 'team-a.orders', true],
    ['team-a.{orders,events}', 'team-a.events', true],
    ['team-a.[xy]1', 'team-a.y1', true],
    ['a.b', 'aXb', false],
  ])('%s vs %s -> %s', (pattern, value, expected) => {
    expect(globMatch(pattern, value)).toBe(expected);
  });
});

describe('diffJson', () => {
  it('reports added, removed and changed paths', () => {
    const d = diffJson({ a: 1, b: { c: [1, 2] }, gone: true }, { a: 2, b: { c: [1, 3] }, added: 'x' });
    expect(d).toEqual([
      { path: '$.a', kind: 'changed', before: 1, after: 2 },
      { path: '$.added', kind: 'added', after: 'x' },
      { path: '$.b.c', kind: 'changed', before: [1, 2], after: [1, 3] },
      { path: '$.gone', kind: 'removed', before: true },
    ]);
  });
  it('is empty for equal documents regardless of key order', () => {
    expect(diffJson({ a: 1, b: 2 }, { b: 2, a: 1 })).toEqual([]);
  });
});

describe('bundle helpers', () => {
  it('reads a ustar archive', () => {
    const enc = new TextEncoder();
    const file = (name: string, content: string) => {
      const header = new Uint8Array(512);
      header.set(enc.encode(name), 0);
      header.set(enc.encode(content.length.toString(8).padStart(11, '0') + '\0'), 124);
      header[156] = '0'.charCodeAt(0);
      const body = new Uint8Array(Math.ceil(content.length / 512) * 512);
      body.set(enc.encode(content));
      return [header, body];
    };
    const parts = [
      ...file('.manifest', '{"revision":"r1","roots":["governance"]}'),
      ...file('governance/x.rego', 'package governance'),
      new Uint8Array(1024),
    ];
    const total = parts.reduce((n, p) => n + p.length, 0);
    const buf = new Uint8Array(total);
    let o = 0;
    for (const p of parts) {
      buf.set(p, o);
      o += p.length;
    }
    const entries = readTar(buf);
    expect(entries.map((e) => e.name)).toEqual(['.manifest', 'governance/x.rego']);
  });
  it('summarises opa test reports the way the server validates them', () => {
    const report = JSON.stringify([
      { package: 'data.governance.a', name: 'test_ok' },
      { package: 'data.governance.a', name: 'test_bad', fail: true },
      { package: 'data.governance.b', name: 'test_skip', skip: true },
    ]);
    expect(summarizeTestReport(report)).toEqual({
      total: 3,
      failed: ['data.governance.a.test_bad', 'data.governance.b.test_skip'],
    });
    expect(() => summarizeTestReport('{}')).toThrow();
  });
});

describe('utils', () => {
  it('shortens prefixed ids', () => {
    expect(shortId('exc-11111111-1111-1111-1111-111111111111')).toBe('exc-11111111…');
    expect(shortId('cfg-initial')).toBe('cfg-initial');
  });
  it('stringifies with sorted keys', () => {
    expect(stableStringify({ b: 1, a: { d: 1, c: 2 } }, 0)).toBe('{"a":{"c":2,"d":1},"b":1}');
  });
});
