import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function shortId(id: string, keep = 8): string {
  const i = id.indexOf('-');
  const prefix = i > 0 ? id.slice(0, i + 1) : '';
  const rest = i > 0 ? id.slice(i + 1) : id;
  return rest.length > keep ? `${prefix}${rest.slice(0, keep)}…` : id;
}

export function shortSha(sha: string, n = 12): string {
  return sha.length > n ? sha.slice(0, n) : sha;
}

export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

export function stableStringify(value: unknown, indent = 2): string {
  return JSON.stringify(sortKeys(value), null, indent);
}

export function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortKeys);
  if (value && typeof value === 'object') {
    return Object.keys(value as Record<string, unknown>)
      .sort()
      .reduce<Record<string, unknown>>((acc, k) => {
        acc[k] = sortKeys((value as Record<string, unknown>)[k]);
        return acc;
      }, {});
  }
  return value;
}
