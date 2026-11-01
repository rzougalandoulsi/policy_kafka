import { useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { toast } from 'sonner';
import { ApiError, describeError } from '@/api/client';
import { useActivateApprovalConfig, useCreateApprovalConfig } from '@/api/hooks';
import type { ApprovalConfiguration } from '@/api/types';
import { Button } from '@/components/ui/button';
import { ChipsInput } from '@/components/ui/chips';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Field, Input, fieldProps } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/states';
import { Switch } from '@/components/ui/switch';
import {
  approvalRuleSchema,
  defaultsFrom,
  describeRule,
  toBody,
  TTL_PRESETS,
  type ApprovalRuleForm,
} from './approvalRuleSchema';

export function NewApprovalRuleDialog({
  open,
  onOpenChange,
  active,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  active: ApprovalConfiguration | undefined;
}) {
  const create = useCreateApprovalConfig();
  const activate = useActivateApprovalConfig();
  const form = useForm<ApprovalRuleForm>({
    resolver: zodResolver(approvalRuleSchema),
    defaultValues: defaultsFrom(active),
  });
  const { register, control, handleSubmit, watch, setValue, setError, reset, formState } = form;

  useEffect(() => {
    if (open) reset(defaultsFrom(active));
  }, [open, active, reset]);

  const values = watch();
  const busy = create.isPending || activate.isPending;

  const submit = handleSubmit(async (f) => {
    try {
      const created = await create.mutateAsync(toBody(f));
      if (f.activateImmediately) {
        await activate.mutateAsync(created.id);
        toast.success(`Approval rule v${created.version} created and activated`);
      } else {
        toast.success(`Approval rule v${created.version} created (not active yet)`);
      }
      onOpenChange(false);
    } catch (e) {
      if (e instanceof ApiError) {
        const fields = e.fieldErrors();
        const known = Object.keys(fields).filter((k) => k in f);
        for (const k of known) setError(k as keyof ApprovalRuleForm, { message: fields[k] });
        if (known.length === 0) toast.error(describeError(e));
      } else toast.error(describeError(e));
    }
  });

  const err = (k: keyof ApprovalRuleForm) => formState.errors[k]?.message as string | undefined;

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent
        title="New approval rule version"
        description="Creates a new version of the approver roles, quorum, separation of duties and expiry. It only applies to approvals opened after it is activated."
        footer={
          <>
            <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={busy}>
              Cancel
            </Button>
            <Button form="new-approval-rule" type="submit" loading={busy}>
              {values.activateImmediately ? 'Create and activate' : 'Create version'}
            </Button>
          </>
        }
      >
        <form id="new-approval-rule" onSubmit={submit} className="flex flex-col gap-4" noValidate>
          <Field
            id="approverRoles"
            label="Approver roles"
            required
            error={err('approverRoles')}
            hint="Press Enter or comma to add a role."
          >
            <Controller
              control={control}
              name="approverRoles"
              render={({ field }) => (
                <ChipsInput
                  id="approverRoles"
                  value={field.value}
                  onChange={field.onChange}
                  suggestions={['governance-approver']}
                  disabled={busy}
                />
              )}
            />
          </Field>

          <Field
            id="requiredApprovals"
            label="Required approvals"
            required
            error={err('requiredApprovals')}
            hint="Quorum: between 1 and 10 distinct approvers."
          >
            <Input
              type="number"
              min={1}
              max={10}
              step={1}
              {...fieldProps('requiredApprovals', err('requiredApprovals'), true)}
              {...register('requiredApprovals')}
              disabled={busy}
            />
          </Field>

          <div className="flex items-center justify-between gap-4">
            <div>
              <label htmlFor="separationOfDuties" className="text-sm font-medium">
                Separation of duties
              </label>
              <p className="text-xs text-muted">The requester can never approve their own request.</p>
            </div>
            <Controller
              control={control}
              name="separationOfDuties"
              render={({ field }) => (
                <Switch
                  id="separationOfDuties"
                  checked={field.value}
                  onCheckedChange={field.onChange}
                  disabled={busy}
                />
              )}
            />
          </div>

          <Field
            id="ttlHours"
            label="Time to decide (hours)"
            required
            error={err('ttlHours')}
            hint="Pending approvals expire after this many hours (1 to 720)."
          >
            <div className="flex flex-wrap items-center gap-2">
              <Input
                type="number"
                min={1}
                max={720}
                step={1}
                className="w-32"
                {...fieldProps('ttlHours', err('ttlHours'), true)}
                {...register('ttlHours')}
                disabled={busy}
              />
              {TTL_PRESETS.map((h) => (
                <Button
                  key={h}
                  type="button"
                  size="sm"
                  variant={Number(values.ttlHours) === h ? 'primary' : 'secondary'}
                  onClick={() => setValue('ttlHours', h, { shouldValidate: true })}
                  disabled={busy}
                >
                  {h}h
                </Button>
              ))}
            </div>
          </Field>

          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" {...register('activateImmediately')} disabled={busy} />
            Activate immediately (retires the current rule)
          </label>

          <InlineAlert tone="info">
            {describeRule({
              approverRoles: values.approverRoles ?? [],
              requiredApprovals: Number(values.requiredApprovals) || 0,
              separationOfDuties: !!values.separationOfDuties,
              ttlHours: Number(values.ttlHours) || 0,
            })}
          </InlineAlert>
        </form>
      </DialogContent>
    </Dialog>
  );
}
