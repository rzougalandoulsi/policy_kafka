import { useState, type ReactNode } from 'react';
import { Dialog, DialogContent } from './dialog';
import { Button } from './button';

/**
 * Confirmation for irreversible governance actions (activate, rollback, revoke). `onConfirm` may reject;
 * the dialog stays open and shows the error so the operator can retry or cancel.
 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel = 'Confirm',
  tone = 'primary',
  onConfirm,
  children,
  disabled,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  confirmLabel?: string;
  tone?: 'primary' | 'danger';
  onConfirm: () => Promise<unknown> | unknown;
  children?: ReactNode;
  disabled?: boolean;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      await onConfirm();
      onOpenChange(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent
        title={title}
        description={description}
        footer={
          <>
            <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={busy}>
              Cancel
            </Button>
            <Button variant={tone} onClick={run} loading={busy} disabled={disabled}>
              {confirmLabel}
            </Button>
          </>
        }
      >
        {children}
        {error && (
          <p role="alert" className="mt-3 rounded-md bg-danger-bg px-3 py-2 text-sm text-danger">
            {error}
          </p>
        )}
      </DialogContent>
    </Dialog>
  );
}
