import { format, formatDistanceToNowStrict, isValid, parseISO } from 'date-fns';

export function parseInstant(v: string | null | undefined): Date | null {
  if (!v) return null;
  const d = parseISO(v);
  return isValid(d) ? d : null;
}

export function formatInstant(v: string | Date | null | undefined, pattern = 'yyyy-MM-dd HH:mm:ss'): string {
  const d = typeof v === 'string' ? parseInstant(v) : (v ?? null);
  return d ? format(d, pattern) : '—';
}

export function relative(v: string | Date | null | undefined, now = new Date()): string {
  const d = typeof v === 'string' ? parseInstant(v) : (v ?? null);
  if (!d) return '—';
  const suffix = d.getTime() < now.getTime() ? 'ago' : 'from now';
  return `${formatDistanceToNowStrict(d)} ${suffix}`;
}

/** For <input type="datetime-local">: local wall-clock without seconds. */
export function toDateTimeLocal(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function fromDateTimeLocal(v: string): Date | null {
  if (!v) return null;
  const d = new Date(v);
  return isValid(d) ? d : null;
}

export function hoursFromNow(h: number, now = new Date()): Date {
  return new Date(now.getTime() + h * 3_600_000);
}

export function daysFromNow(d: number, now = new Date()): Date {
  return hoursFromNow(d * 24, now);
}
