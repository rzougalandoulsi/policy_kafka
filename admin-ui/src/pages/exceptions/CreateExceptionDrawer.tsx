import { useMemo, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { ApiError, describeError } from '@/api/client';
import { useCatalog, useCreateException } from '@/api/hooks';
import { KNOWN_ACTION_TYPES, type CatalogPolicy } from '@/api/types';
import { Button } from '@/components/ui/button';
import { ChipsInput } from '@/components/ui/chips';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Field, Input, Select, Textarea, fieldProps } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/states';
import { globMatch } from '@/lib/glob';
import { daysFromNow, hoursFromNow, toDateTimeLocal } from '@/lib/time';
import {
  emptyExceptionForm,
  exceptionSchema,
  toCreateBody,
  type ExceptionFormValues,
} from './exceptionSchema';

const SECTION_ORDER = ['naming', 'rbac', 'limits', 'destructive', 'approvals'] as const;
const PRESETS: Array<{ label: string; at: () => Date }> = [
  { label: '24h', at: () => hoursFromNow(24) },
  { label: '7d', at: () => daysFromNow(7) },
  { label: '30d', at: () => daysFromNow(30) },
  { label: '90d', at: () => daysFromNow(90) },
];

export function groupBySection(policies: CatalogPolicy[]): Array<[string, CatalogPolicy[]]> {
  const map = new Map<string, CatalogPolicy[]>();
  for (const p of policies) map.set(p.section, [...(map.get(p.section) ?? []), p]);
  const known = SECTION_ORDER.filter((s) => map.has(s)).map(
    (s) => [s, map.get(s)!] as [string, CatalogPolicy[]],
  );
  const other = Array.from(map.entries()).filter(([s]) => !(SECTION_ORDER as readonly string[]).includes(s));
  return [...known, ...other];
}

export function CreateExceptionDrawer({
  open,
  onOpenChange,
  defaultTeamId,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  defaultTeamId?: string;
}) {
  const navigate = useNavigate();
  const catalog = useCatalog({ enabled: open });
  const create = useCreateException();
  const [sample, setSample] = useState('');

  const form = useForm<ExceptionFormValues>({
    resolver: zodResolver(exceptionSchema),
    defaultValues: { ...emptyExceptionForm, teamId: defaultTeamId ?? '' },
    mode: 'onBlur',
  });
  const { register, handleSubmit, control, setValue, setError, watch, reset, formState } = form;
  const errors = formState.errors;

  const grouped = useMemo(() => groupBySection(catalog.data ?? []), [catalog.data]);
  const selectedPolicy = catalog.data?.find((p) => p.policyId === watch('policyId'));
  const pattern = watch('resourcePattern') || '*';
  const sampleMatches = sample ? globMatch(pattern, sample) : null;

  const close = (o: boolean) => {
    if (!o) {
      reset({ ...emptyExceptionForm, teamId: defaultTeamId ?? '' });
      setSample('');
    }
    onOpenChange(o);
  };

  const submit = handleSubmit(async (values) => {
    try {
      const created = await create.mutateAsync(toCreateBody(values));
      toast.success(`Exception created for ${created.policyId}`);
      close(false);
      navigate(`/exceptions/${encodeURIComponent(created.id)}`);
    } catch (e) {
      if (e instanceof ApiError) {
        const fields = e.fieldErrors();
        const known = Object.keys(fields).filter((k) => k in emptyExceptionForm) as Array<
          keyof ExceptionFormValues
        >;
        known.forEach((k) => setError(k, { type: 'server', message: fields[k] }));
        if (known.length === 0) toast.error(describeError(e));
      } else toast.error(describeError(e));
    }
  });

  const catalogUnavailable = catalog.isError || (catalog.isSuccess && (catalog.data?.length ?? 0) === 0);

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent
        side="right"
        title="New scoped exception"
        description="A time-boxed exemption from one bypassable policy for one team. Never a blanket whitelist."
        footer={
          <>
            <Button variant="secondary" onClick={() => close(false)} disabled={create.isPending}>
              Cancel
            </Button>
            <Button type="submit" form="create-exception-form" loading={create.isPending}>
              Create exception
            </Button>
          </>
        }
      >
        <form id="create-exception-form" onSubmit={submit} className="flex flex-col gap-4" noValidate>
          <Field
            id="exc-policy"
            label="Policy"
            required
            error={errors.policyId?.message}
            hint={
              selectedPolicy
                ? `${selectedPolicy.severity} · ${selectedPolicy.section} · ${selectedPolicy.description}`
                : 'Only bypassable policies can be exempted.'
            }
          >
            {catalogUnavailable ? (
              <div className="flex flex-col gap-2">
                <InlineAlert tone="warn">
                  The policy catalogue is unavailable, so the picker cannot tell which policies are
                  bypassable. Enter the policy id manually.
                </InlineAlert>
                <Input
                  {...register('policyId')}
                  {...fieldProps('exc-policy', errors.policyId?.message)}
                  placeholder="MAX_PARTITIONS_PER_TOPIC"
                  className="mono"
                />
              </div>
            ) : (
              <Select
                {...register('policyId')}
                {...fieldProps('exc-policy', errors.policyId?.message, true)}
                disabled={catalog.isLoading}
              >
                <option value="">{catalog.isLoading ? 'Loading catalogue…' : 'Select a policy'}</option>
                {grouped.map(([section, policies]) => (
                  <optgroup key={section} label={section}>
                    {policies.map((p) => (
                      <option key={p.policyId} value={p.policyId} disabled={!p.bypassable}>
                        {p.policyId}
                        {p.bypassable ? '' : ' (not bypassable)'}
                      </option>
                    ))}
                  </optgroup>
                ))}
              </Select>
            )}
          </Field>
          {selectedPolicy && !selectedPolicy.bypassable && (
            <InlineAlert tone="danger">
              {selectedPolicy.policyId} is {selectedPolicy.severity}: an exception on it would never apply.
            </InlineAlert>
          )}

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field id="exc-team" label="Team" required error={errors.teamId?.message}>
              <Input
                {...register('teamId')}
                {...fieldProps('exc-team', errors.teamId?.message)}
                placeholder="team-a"
              />
            </Field>
            <Field
              id="exc-env"
              label="Environment"
              hint='Empty = any environment ("*").'
              error={errors.environment?.message}
            >
              <Input
                {...register('environment')}
                {...fieldProps('exc-env', errors.environment?.message, true)}
                placeholder="production"
              />
            </Field>
            <Field
              id="exc-principal"
              label="Principal"
              hint="Empty = any principal of the team."
              error={errors.principalId?.message}
            >
              <Input
                {...register('principalId')}
                {...fieldProps('exc-principal', errors.principalId?.message, true)}
                placeholder="dev-1"
              />
            </Field>
            <Field
              id="exc-pattern"
              label="Resource pattern"
              hint='Glob, empty = "*".'
              error={errors.resourcePattern?.message}
            >
              <Input
                {...register('resourcePattern')}
                {...fieldProps('exc-pattern', errors.resourcePattern?.message, true)}
                placeholder="team-a.orders.*"
                className="mono"
              />
            </Field>
          </div>

          <Field
            id="exc-sample"
            label="Try a resource name"
            hint={
              sampleMatches === null
                ? `Checks a name against "${pattern}".`
                : sampleMatches
                  ? `"${sample}" matches ${pattern}`
                  : `"${sample}" does not match ${pattern}`
            }
          >
            <Input
              id="exc-sample"
              value={sample}
              onChange={(e) => setSample(e.target.value)}
              placeholder="team-a.orders.v2"
              className="mono"
            />
          </Field>
          {sample && (
            <p role="status" className={sampleMatches ? 'text-xs text-ok' : 'text-xs text-warn'}>
              {sampleMatches ? 'Match' : 'No match'}
            </p>
          )}

          <Field
            id="exc-actions"
            label="Action types"
            hint="Only applies at plan stage. Empty = any action."
            error={errors.actionTypes?.message}
          >
            <Controller
              control={control}
              name="actionTypes"
              render={({ field }) => (
                <ChipsInput
                  id="exc-actions"
                  value={field.value}
                  onChange={field.onChange}
                  suggestions={KNOWN_ACTION_TYPES}
                  placeholder="TOPIC_DELETE"
                />
              )}
            />
          </Field>

          <Field
            id="exc-expires"
            label="Expires at"
            required
            error={errors.expiresAt?.message}
            hint="At most 365 days ahead."
          >
            <div className="flex flex-col gap-2">
              <Input
                type="datetime-local"
                {...register('expiresAt')}
                {...fieldProps('exc-expires', errors.expiresAt?.message, true)}
              />
              <div className="flex flex-wrap gap-1" role="group" aria-label="Expiry presets">
                {PRESETS.map((p) => (
                  <Button
                    key={p.label}
                    type="button"
                    size="sm"
                    variant="secondary"
                    onClick={() =>
                      setValue('expiresAt', toDateTimeLocal(p.at()), {
                        shouldValidate: true,
                        shouldDirty: true,
                      })
                    }
                  >
                    {p.label}
                  </Button>
                ))}
              </div>
            </div>
          </Field>

          <Field id="exc-reason" label="Reason" required error={errors.reason?.message}>
            <Textarea
              {...register('reason')}
              {...fieldProps('exc-reason', errors.reason?.message)}
              rows={3}
              placeholder="Ticket, incident or justification"
            />
          </Field>
        </form>
      </DialogContent>
    </Dialog>
  );
}
