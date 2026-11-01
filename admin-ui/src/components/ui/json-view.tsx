import { useState } from 'react';
import { Check, Copy } from 'lucide-react';
import { copyToClipboard, stableStringify } from '@/lib/utils';
import { Button } from './button';
import { cn } from '@/lib/utils';

export function JsonView({
  value,
  className,
  maxHeight = '24rem',
}: {
  value: unknown;
  className?: string;
  maxHeight?: string;
}) {
  const [copied, setCopied] = useState(false);
  const text = stableStringify(value);
  return (
    <div className={cn('relative rounded-md border border-border bg-surface-2', className)}>
      <Button
        variant="ghost"
        size="icon"
        className="absolute right-1 top-1"
        aria-label="Copy JSON"
        onClick={async () => {
          if (await copyToClipboard(text)) {
            setCopied(true);
            setTimeout(() => setCopied(false), 1200);
          }
        }}
      >
        {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
      </Button>
      <pre className="overflow-auto p-3 text-xs leading-5" style={{ maxHeight }}>
        {text}
      </pre>
    </div>
  );
}
