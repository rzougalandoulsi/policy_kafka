import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';

/** Definition list for record details. */
export function KV({
  items,
  className,
}: {
  items: Array<{ k: ReactNode; v: ReactNode; mono?: boolean }>;
  className?: string;
}) {
  return (
    <dl className={cn('grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-[max-content_1fr]', className)}>
      {items.map((it, i) => (
        <div key={i} className="contents">
          <dt className="text-muted">{it.k}</dt>
          <dd className={cn('break-all', it.mono && 'mono text-xs')}>{it.v ?? '—'}</dd>
        </div>
      ))}
    </dl>
  );
}
