import type { HTMLAttributes } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

const badgeVariants = cva(
  'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap',
  {
    variants: {
      tone: {
        neutral: 'bg-neutral-bg text-text',
        ok: 'bg-ok-bg text-ok',
        warn: 'bg-warn-bg text-warn',
        danger: 'bg-danger-bg text-danger',
        info: 'bg-info-bg text-info',
      },
    },
    defaultVariants: { tone: 'neutral' },
  },
);

export type BadgeTone = NonNullable<VariantProps<typeof badgeVariants>['tone']>;

export function Badge({
  className,
  tone,
  ...props
}: HTMLAttributes<HTMLSpanElement> & VariantProps<typeof badgeVariants>) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />;
}

/** Colour mapping for VersionStatus and other enums shared across pages. */
export function statusTone(status: string): BadgeTone {
  switch (status) {
    case 'ACTIVE':
    case 'APPROVED':
    case 'COMPLETED':
      return 'ok';
    case 'DRAFT':
    case 'UPLOADED':
    case 'PENDING':
      return 'info';
    case 'RETIRED':
    case 'CANCELLED':
      return 'neutral';
    case 'FAILED':
    case 'REJECTED':
    case 'EXPIRED':
      return 'danger';
    default:
      return 'neutral';
  }
}

export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={statusTone(status)}>{status}</Badge>;
}
