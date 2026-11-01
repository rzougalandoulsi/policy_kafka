import { ConfirmDialog } from '@/components/ui/confirm-dialog';
import { InlineAlert } from '@/components/ui/states';
import type { PolicyConfiguration } from '@/api/types';
import { ConfigDiff } from './ConfigDiff';

/** Confirmation for activating `target`; `active` is the version it retires (if any). */
export function ActivateDialog({
  open,
  onOpenChange,
  target,
  active,
  onConfirm,
  title,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  target: PolicyConfiguration | null;
  active: PolicyConfiguration | null | undefined;
  onConfirm: () => Promise<unknown>;
  title?: string;
}) {
  if (!target) return null;
  const retires = active && active.id !== target.id ? active : null;
  return (
    <ConfirmDialog
      open={open}
      onOpenChange={onOpenChange}
      title={title ?? `Activate version ${target.version}`}
      description={
        retires
          ? `Activating v${target.version} retires the current ACTIVE v${retires.version}. Every new decision will be evaluated against v${target.version}.`
          : `v${target.version} becomes the ACTIVE configuration.`
      }
      confirmLabel="Activate"
      onConfirm={onConfirm}
    >
      {retires ? (
        <>
          <p className="mb-2 text-sm font-medium">Changes vs. active v{retires.version}</p>
          <ConfigDiff
            before={retires.content}
            after={target.content}
            beforeLabel={`v${retires.version}`}
            afterLabel={`v${target.version}`}
          />
        </>
      ) : (
        <InlineAlert tone="warn">No ACTIVE version to compare against.</InlineAlert>
      )}
    </ConfirmDialog>
  );
}
