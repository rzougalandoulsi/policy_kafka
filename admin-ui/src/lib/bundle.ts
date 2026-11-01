import { gunzipSync } from 'fflate';

/** Reads a `opa build` tar.gz in the browser: the .manifest and the list of .rego files. */
export interface BundleInspection {
  revision: string | null;
  roots: string[];
  regoFiles: string[];
  manifest: Record<string, unknown> | null;
}

export function inspectBundle(bytes: Uint8Array): BundleInspection {
  const tar = gunzipSync(bytes);
  const entries = readTar(tar);
  let manifest: Record<string, unknown> | null = null;
  const regoFiles: string[] = [];
  for (const e of entries) {
    const name = e.name.replace(/^\/+/, '').replace(/^\.\//, '');
    if (name === '.manifest' || name.endsWith('/.manifest')) {
      manifest = JSON.parse(new TextDecoder().decode(e.data)) as Record<string, unknown>;
    } else if (name.endsWith('.rego')) regoFiles.push(name);
  }
  const revision = manifest && manifest.revision != null ? String(manifest.revision) : null;
  const roots = manifest && Array.isArray(manifest.roots) ? (manifest.roots as unknown[]).map(String) : [];
  return { revision, roots, regoFiles, manifest };
}

interface TarEntry {
  name: string;
  data: Uint8Array;
}

/** POSIX ustar reader sufficient for OPA bundles (handles the ././@LongLink GNU extension). */
export function readTar(buf: Uint8Array): TarEntry[] {
  const out: TarEntry[] = [];
  const dec = new TextDecoder();
  let offset = 0;
  let pendingLongName: string | null = null;
  while (offset + 512 <= buf.length) {
    const header = buf.subarray(offset, offset + 512);
    if (header.every((b) => b === 0)) break;
    const rawName = cstr(dec.decode(header.subarray(0, 100)));
    const size = parseInt(cstr(dec.decode(header.subarray(124, 136))).trim() || '0', 8);
    const type = String.fromCharCode(header[156]);
    const prefix = cstr(dec.decode(header.subarray(345, 500)));
    const data = buf.subarray(offset + 512, offset + 512 + size);
    offset += 512 + Math.ceil(size / 512) * 512;
    if (type === 'L') {
      pendingLongName = cstr(dec.decode(data));
      continue;
    }
    const name = pendingLongName ?? (prefix ? `${prefix}/${rawName}` : rawName);
    pendingLongName = null;
    if (type === '0' || type === '\0' || type === '') out.push({ name, data });
  }
  return out;
}

function cstr(s: string): string {
  const i = s.indexOf('\0');
  return i >= 0 ? s.slice(0, i) : s;
}

export function toBase64(bytes: Uint8Array): string {
  let bin = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) bin += String.fromCharCode(...bytes.subarray(i, i + chunk));
  return btoa(bin);
}

/** Summary of an `opa test --format=json` report as PolicyBundleService.validateTestReport sees it. */
export interface TestReportSummary {
  total: number;
  failed: string[];
}

export function summarizeTestReport(json: string): TestReportSummary {
  const parsed = JSON.parse(json) as unknown;
  if (!Array.isArray(parsed)) throw new Error('Test report must be a JSON array (opa test --format=json)');
  const failed = parsed
    .filter((e) => e && typeof e === 'object' && (e.fail === true || e.skip === true || e.error != null))
    .map((e) => `${String(e.package)}.${String(e.name)}`);
  return { total: parsed.length, failed };
}
