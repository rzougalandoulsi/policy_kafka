import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';

export function Spinner({
  label = 'Loading',
  full = false,
  className,
}: {
  label?: string;
  full?: boolean;
  className?: string;
}) {
  return (
    <div
      role="status"
      aria-live="polite"
      className={cn(
        'flex items-center gap-2 text-sm text-muted',
        full && 'h-[50vh] justify-center',
        className,
      )}
    >
      <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
      <span>{label}…</span>
    </div>
  );
}
