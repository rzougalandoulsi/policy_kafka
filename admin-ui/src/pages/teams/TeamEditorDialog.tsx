import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { toast } from 'sonner';
import { ApiError, describeError } from '@/api/client';
import { useUpsertTeam } from '@/api/hooks';
import { COUNTED_QUOTA_KEYS, QUOTA_KEYS, type TeamConfiguration } from '@/api/types';
import { Button } from '@/components/ui/button';
import { ChipsInput } from '@/components/ui/chips';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Field, Input, Select, Textarea, fieldProps } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/states';
import { defaultsFrom, describeTeam, teamSchema, toBody, type TeamForm } from './teamSchema';

/**
 * Create or edit one team's configuration. Editing a team owned by an external registry is only possible by taking
 * ownership (source = governance); the API refuses everything else with 409 TEAM_CONFIGURATION_EXTERNAL.
 */
export function TeamEditorDialog({
  open,
  onOpenChange,
  team,
  onSaved,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** undefined = create a new team */
  team: TeamConfiguration | undefined;
  onSaved?: (saved: TeamConfiguration) => void;
}) {
  const upsert = useUpsertTeam();
  const creating = !team;
  const form = useForm<TeamForm>({ resolver: zodResolver(teamSchema), defaultValues: defaultsFrom(team) });
  const { register, control, handleSubmit, watch, setError, reset, formState } = form;
  const [apiError, setApiError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      reset(defaultsFrom(team));
      setApiError(null);
    }
  }, [open, team, reset]);

  const values = watch();
  const busy = upsert.isPending;
  const external = team?.source === 'external';

  const submit = handleSubmit(async (f) => {
    setApiError(null);
    try {
      const saved = await upsert.mutateAsync({ teamId: f.teamId, body: toBody(f) });
      toast.success(`Team ${saved.teamId} is now at version ${saved.version}`);
      onOpenChange(false);
      onSaved?.(saved);
    } catch (e) {
      if (e instanceof ApiError) {
        const fields = e.fieldErrors();
        const known = Object.keys(fields).filter((k) => k in f);
        for (const k of known) setError(k as keyof TeamForm, { message: fields[k] });
        if (known.length === 0) setApiError(describeError(e));
      } else setApiError(describeError(e));
    }
  });

  const err = (k: keyof TeamForm) => formState.errors[k]?.message as string | undefined;
  const quotaErr = (k: (typeof QUOTA_KEYS)[number]) => formState.errors.quotas?.[k]?.message as string | undefined;

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent
        side="right"
        title={creating ? 'New team configuration' : `Edit ${team.teamId}`}
        description="Authorised prefixes, quotas per resource type and role overrides supplied to the policies as input.team. Saving creates a new version; it applies to the next evaluation."
        footer={
          <>
            <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={busy}>
              Cancel
            </Button>
            <Button form="team-editor" type="submit" loading={busy}>
              {creating ? 'Create' : 'Save new version'}
            </Button>
          </>
        }
      >
        <form id="team-editor" onSubmit={submit} className="flex flex-col gap-4" noValidate>
          {external && values.source === 'external' && (
            <InlineAlert tone="warn">
              This team is owned by an external registry ({team.sourceVersion}). Governance refuses edits unless you
              set the source to <strong>governance</strong>, which takes ownership back (audited).
            </InlineAlert>
          )}
          <Field id="teamId" label="Team id" required error={err('teamId')} hint="Matches the teams claim of the requesters' tokens.">
            <Input {...fieldProps('teamId', err('teamId'), true)} {...register('teamId')} disabled={busy || !creating} className="mono" />
          </Field>

          <Field
            id="authorizedPrefixes"
            label="Authorised topic prefixes"
            required
            error={err('authorizedPrefixes')}
            hint="A topic name must start with one of them (case-insensitive) while naming.requireTeamPrefix is on. Enter or comma adds."
          >
            <Controller
              control={control}
              name="authorizedPrefixes"
              render={({ field }) => (
                <ChipsInput
                  id="authorizedPrefixes"
                  value={field.value}
                  onChange={field.onChange}
                  placeholder={values.teamId ? `${values.teamId}.` : 'team-a.'}
                  disabled={busy}
                />
              )}
            />
          </Field>

          <fieldset className="flex flex-col gap-3 rounded-md border border-border p-3">
            <legend className="px-1 text-sm font-medium">Quotas</legend>
            <p className="text-xs text-muted">
              Upper bound on the team&apos;s projected usage (confirmed + reserved by in-flight plans + the change) per
              environment, across every repository. Empty means unlimited.
            </p>
            <div className="grid gap-3 sm:grid-cols-2">
              {QUOTA_KEYS.map((k) => (
                <Field
                  key={k}
                  id={`quota-${k}`}
                  label={k}
                  error={quotaErr(k)}
                  hint={COUNTED_QUOTA_KEYS.includes(k) ? undefined : 'not counted yet (resource type not in the contracts)'}
                >
                  <Input
                    type="number"
                    min={0}
                    step={1}
                    inputMode="numeric"
                    placeholder="unlimited"
                    {...fieldProps(`quota-${k}`, quotaErr(k), true)}
                    {...register(`quotas.${k}`)}
                    disabled={busy}
                  />
                </Field>
              ))}
            </div>
          </fieldset>

          <Field
            id="allowedRoles"
            label="Allowed roles (override)"
            error={err('allowedRoles')}
            hint="When set, RBAC_TEAM_ROLE_ALLOWED only permits these roles for the team; empty falls back to rbac.teamRoles."
          >
            <Controller
              control={control}
              name="allowedRoles"
              render={({ field }) => (
                <ChipsInput
                  id="allowedRoles"
                  value={field.value}
                  onChange={field.onChange}
                  suggestions={['DeveloperRead', 'DeveloperWrite', 'DeveloperManage', 'ResourceOwner']}
                  disabled={busy}
                />
              )}
            />
          </Field>

          <Field
            id="environmentsJson"
            label="Environment parameters (JSON)"
            error={err('environmentsJson')}
            hint='Optional, passed through as input.team.environments, e.g. {"production": {"changeWindow": "weekdays"}}.'
          >
            <Textarea
              {...fieldProps('environmentsJson', err('environmentsJson'), true)}
              {...register('environmentsJson')}
              rows={4}
              spellCheck={false}
              className="mono text-xs"
              disabled={busy}
            />
          </Field>

          <div className="grid gap-3 sm:grid-cols-2">
            <Field id="source" label="Source" error={err('source')} hint="Who owns this record.">
              <Select {...fieldProps('source', err('source'), true)} {...register('source')} disabled={busy}>
                <option value="governance">governance (system of record)</option>
                <option value="external">external registry (read-only here)</option>
              </Select>
            </Field>
            {values.source === 'external' && (
              <Field id="sourceVersion" label="Registry version" required error={err('sourceVersion')}>
                <Input {...fieldProps('sourceVersion', err('sourceVersion'))} {...register('sourceVersion')} disabled={busy} />
              </Field>
            )}
          </div>

          <Field id="comment" label="Comment" error={err('comment')} hint="Why this version exists; recorded in history and audit.">
            <Input {...fieldProps('comment', err('comment'), true)} {...register('comment')} disabled={busy} />
          </Field>

          {apiError && <InlineAlert tone="danger">{apiError}</InlineAlert>}

          <InlineAlert tone="info">
            {describeTeam({
              authorizedPrefixes: values.authorizedPrefixes ?? [],
              quotas: Object.fromEntries(
                Object.entries(values.quotas ?? {})
                  .filter(([, v]) => v !== '' && v !== undefined)
                  .map(([k, v]) => [k, Number(v)]),
              ),
              allowedRoles: values.allowedRoles ?? [],
            })}
          </InlineAlert>
        </form>
      </DialogContent>
    </Dialog>
  );
}
