import { useState, type ReactNode } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Field, Input, fieldProps } from '@/components/ui/form';
import { Switch } from '@/components/ui/switch';
import { ChipsInput } from '@/components/ui/chips';
import type { EnforcementMode, PolicyConfigurationContent } from '@/api/types';
import { useCatalog } from '@/api/hooks';
import { Select } from '@/components/ui/form';
import { Badge } from '@/components/ui/badge';
import { LIMIT_KEYS, type LimitKey } from './configSchema';

type Content = PolicyConfigurationContent;
type Patch<S extends keyof Content> = (fn: (section: Content[S]) => Content[S]) => void;

function TextField({
  id,
  label,
  value,
  onChange,
  hint,
  error,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  hint?: string;
  error?: string;
}) {
  return (
    <Field id={id} label={label} hint={hint} error={error}>
      <Input
        {...fieldProps(id, error, hint)}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="mono"
      />
    </Field>
  );
}

function NumberField({
  id,
  label,
  value,
  onChange,
  hint,
}: {
  id: string;
  label: string;
  value: number | undefined;
  onChange: (v: number | undefined) => void;
  hint?: string;
}) {
  const invalid = value !== undefined && !(value > 0);
  const error = invalid ? 'must be a positive number' : undefined;
  return (
    <Field id={id} label={label} hint={hint} error={error}>
      <Input
        {...fieldProps(id, error, hint)}
        type="number"
        inputMode="numeric"
        min={1}
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value === '' ? undefined : Number(e.target.value))}
      />
    </Field>
  );
}

function BoolField({
  id,
  label,
  value,
  onChange,
  hint,
}: {
  id: string;
  label: string;
  value: boolean;
  onChange: (v: boolean) => void;
  hint?: string;
}) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-md border border-border px-3 py-2">
      <div>
        <label htmlFor={id} className="text-sm font-medium">
          {label}
        </label>
        {hint && <p className="text-xs text-muted">{hint}</p>}
      </div>
      <Switch id={id} checked={value} onCheckedChange={onChange} />
    </div>
  );
}

function ListField({
  id,
  label,
  value,
  onChange,
  hint,
}: {
  id: string;
  label: string;
  value: string[];
  onChange: (v: string[]) => void;
  hint?: string;
}) {
  return (
    <Field id={id} label={label} hint={hint}>
      <ChipsInput id={id} value={value ?? []} onChange={onChange} placeholder="Type and press Enter" />
    </Field>
  );
}

/** Generic key → value map editor; `renderValue` renders the editor for one entry. */
function MapField<V>({
  id,
  label,
  value,
  onChange,
  emptyValue,
  keyLabel,
  renderValue,
  hint,
}: {
  id: string;
  label: string;
  value: Record<string, V>;
  onChange: (v: Record<string, V>) => void;
  emptyValue: V;
  keyLabel: string;
  renderValue: (key: string, v: V, set: (v: V) => void) => ReactNode;
  hint?: string;
}) {
  const [newKey, setNewKey] = useState('');
  const entries = Object.entries(value ?? {});
  const add = () => {
    const k = newKey.trim();
    if (!k || k in (value ?? {})) return;
    onChange({ ...value, [k]: emptyValue });
    setNewKey('');
  };
  return (
    <fieldset className="flex flex-col gap-3 rounded-md border border-border p-3">
      <legend className="px-1 text-sm font-medium">{label}</legend>
      {hint && <p className="text-xs text-muted">{hint}</p>}
      {entries.length === 0 && <p className="text-xs text-muted">No entries.</p>}
      {entries.map(([k, v]) => (
        <div key={k} className="flex flex-col gap-2 rounded-md bg-surface-2 p-3">
          <div className="flex items-center justify-between">
            <code className="mono text-xs font-semibold">{k}</code>
            <Button
              size="icon"
              variant="ghost"
              aria-label={`Remove ${k}`}
              onClick={() => {
                const next = { ...value };
                delete next[k];
                onChange(next);
              }}
            >
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
          {renderValue(k, v, (nv) => onChange({ ...value, [k]: nv }))}
        </div>
      ))}
      <div className="flex items-end gap-2">
        <Field id={`${id}-new`} label={`Add ${keyLabel}`} className="flex-1">
          <Input
            id={`${id}-new`}
            value={newKey}
            onChange={(e) => setNewKey(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                add();
              }
            }}
            placeholder={keyLabel}
          />
        </Field>
        <Button variant="secondary" onClick={add} disabled={!newKey.trim()}>
          <Plus className="h-4 w-4" /> Add
        </Button>
      </div>
    </fieldset>
  );
}

// ---------------------------------------------------------------- sections

export function NamingEditor({ value, patch }: { value: Content['naming']; patch: Patch<'naming'> }) {
  let patternError: string | undefined;
  try {
    new RegExp(value.topicPattern ?? '');
  } catch {
    patternError = 'not a valid regular expression';
  }
  return (
    <div className="flex flex-col gap-4">
      <TextField
        id="naming-topicPattern"
        label="topicPattern"
        value={value.topicPattern ?? ''}
        onChange={(v) => patch((s) => ({ ...s, topicPattern: v }))}
        hint="Regular expression every topic name must match (TOPIC_NAME_PATTERN)."
        error={patternError}
      />
      <BoolField
        id="naming-requireTeamPrefix"
        label="requireTeamPrefix"
        value={!!value.requireTeamPrefix}
        onChange={(v) => patch((s) => ({ ...s, requireTeamPrefix: v }))}
        hint="Topics must start with one of the team's authorised prefixes, from the team configuration (AUTHORIZED_PREFIX_REQUIRED)."
      />
      <TextField
        id="naming-teamPrefixSeparator"
        label="teamPrefixSeparator"
        value={value.teamPrefixSeparator ?? ''}
        onChange={(v) => patch((s) => ({ ...s, teamPrefixSeparator: v }))}
      />
      <MapField
        id="naming-envRestrictions"
        label="environmentRestrictions"
        keyLabel="environment"
        hint="Per environment: forbidden name patterns (ENV_NAME_RESTRICTION)."
        value={value.environmentRestrictions ?? {}}
        emptyValue={{ forbiddenPatterns: [] }}
        onChange={(m) => patch((s) => ({ ...s, environmentRestrictions: m }))}
        renderValue={(k, v, set) => (
          <ListField
            id={`naming-envRestrictions-${k}`}
            label="forbiddenPatterns"
            value={v.forbiddenPatterns ?? []}
            onChange={(l) => set({ ...v, forbiddenPatterns: l })}
          />
        )}
      />
    </div>
  );
}

export function RbacEditor({ value, patch }: { value: Content['rbac']; patch: Patch<'rbac'> }) {
  return (
    <div className="flex flex-col gap-4">
      <ListField
        id="rbac-allowedRoles"
        label="allowedRoles"
        value={value.allowedRoles}
        onChange={(l) => patch((s) => ({ ...s, allowedRoles: l }))}
        hint="Only these roles may be requested (RBAC_ROLE_ALLOWED)."
      />
      <ListField
        id="rbac-forbiddenRoles"
        label="forbiddenRoles"
        value={value.forbiddenRoles}
        onChange={(l) => patch((s) => ({ ...s, forbiddenRoles: l }))}
        hint="Administrative roles; HARD_DENY, never exemptable (RBAC_ADMIN_ROLE_FORBIDDEN)."
      />
      <ListField
        id="rbac-sensitiveRoles"
        label="sensitiveRoles"
        value={value.sensitiveRoles}
        onChange={(l) => patch((s) => ({ ...s, sensitiveRoles: l }))}
        hint="Granting these in a sensitive environment requires approval."
      />
      <MapField
        id="rbac-environmentRoles"
        label="environmentRoles"
        keyLabel="environment"
        hint="Roles permitted per environment (RBAC_ENV_RESTRICTED)."
        value={value.environmentRoles ?? {}}
        emptyValue={[] as string[]}
        onChange={(m) => patch((s) => ({ ...s, environmentRoles: m }))}
        renderValue={(k, v, set) => (
          <ListField id={`rbac-environmentRoles-${k}`} label="roles" value={v} onChange={set} />
        )}
      />
      <MapField
        id="rbac-teamRoles"
        label="teamRoles"
        keyLabel="team id"
        hint="Roles a specific team may grant (RBAC_TEAM_ROLE_ALLOWED)."
        value={value.teamRoles ?? {}}
        emptyValue={[] as string[]}
        onChange={(m) => patch((s) => ({ ...s, teamRoles: m }))}
        renderValue={(k, v, set) => (
          <ListField id={`rbac-teamRoles-${k}`} label="roles" value={v} onChange={set} />
        )}
      />
    </div>
  );
}

const LIMIT_HINTS: Record<LimitKey, string> = {
  maxTopicsPerManifest: 'Topics one manifest may declare.',
  maxPartitionsPerTopic: 'Partitions a single topic may request.',
  maxPartitionsPerPlan: 'Partitions one plan may create in total.',
  maxRetentionMs: 'Maximum retention.ms; infinite retention is never allowed.',
};

export function LimitsEditor({ value, patch }: { value: Content['limits']; patch: Patch<'limits'> }) {
  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-4 sm:grid-cols-2">
        {LIMIT_KEYS.map((k) => (
          <NumberField
            key={k}
            id={`limits-${k}`}
            label={k}
            value={value[k]}
            hint={LIMIT_HINTS[k]}
            onChange={(n) => patch((s) => ({ ...s, [k]: n as number }))}
          />
        ))}
      </div>
      <MapField
        id="limits-environmentOverrides"
        label="environmentOverrides"
        keyLabel="environment"
        hint="Per environment overrides; unset keys fall back to the global limit."
        value={value.environmentOverrides ?? {}}
        emptyValue={{}}
        onChange={(m) => patch((s) => ({ ...s, environmentOverrides: m }))}
        renderValue={(k, v, set) => (
          <div className="grid gap-3 sm:grid-cols-2">
            {LIMIT_KEYS.map((lk) => (
              <NumberField
                key={lk}
                id={`limits-environmentOverrides-${k}-${lk}`}
                label={lk}
                value={v[lk]}
                onChange={(n) => {
                  const next = { ...v };
                  if (n === undefined) delete next[lk];
                  else next[lk] = n;
                  set(next);
                }}
              />
            ))}
          </div>
        )}
      />
    </div>
  );
}

export function DestructiveEditor({
  value,
  patch,
}: {
  value: Content['destructive'];
  patch: Patch<'destructive'>;
}) {
  return (
    <div className="flex flex-col gap-4">
      <ListField
        id="destructive-topicDelete"
        label="topicDeleteAllowedEnvironments"
        value={value.topicDeleteAllowedEnvironments}
        onChange={(l) => patch((s) => ({ ...s, topicDeleteAllowedEnvironments: l }))}
        hint="TOPIC_DELETE_RESTRICTED"
      />
      <ListField
        id="destructive-rbacRevoke"
        label="rbacRevokeAllowedEnvironments"
        value={value.rbacRevokeAllowedEnvironments}
        onChange={(l) => patch((s) => ({ ...s, rbacRevokeAllowedEnvironments: l }))}
        hint="RBAC_REVOKE_RESTRICTED"
      />
      <ListField
        id="destructive-identityPoolDelete"
        label="identityPoolDeleteAllowedEnvironments"
        value={value.identityPoolDeleteAllowedEnvironments}
        onChange={(l) => patch((s) => ({ ...s, identityPoolDeleteAllowedEnvironments: l }))}
        hint="IDENTITY_POOL_DELETE_RESTRICTED"
      />
    </div>
  );
}

export function ApprovalsEditor({
  value,
  patch,
}: {
  value: Content['approvals'];
  patch: Patch<'approvals'>;
}) {
  return (
    <div className="flex flex-col gap-4">
      <BoolField
        id="approvals-productionTopicDeletion"
        label="productionTopicDeletion"
        value={!!value.productionTopicDeletion}
        onChange={(v) => patch((s) => ({ ...s, productionTopicDeletion: v }))}
        hint="Deleting topics in a sensitive environment requires approval."
      />
      <BoolField
        id="approvals-sensitiveRoleAssignment"
        label="sensitiveRoleAssignment"
        value={!!value.sensitiveRoleAssignment}
        onChange={(v) => patch((s) => ({ ...s, sensitiveRoleAssignment: v }))}
        hint="Granting rbac.sensitiveRoles in a sensitive environment requires approval."
      />
      <NumberField
        id="approvals-highImpactPartitionThreshold"
        label="highImpactPartitionThreshold"
        value={value.highImpactPartitionThreshold}
        onChange={(n) => patch((s) => ({ ...s, highImpactPartitionThreshold: n as number }))}
        hint="Plans creating at least this many partitions require approval."
      />
      <ListField
        id="approvals-sensitiveEnvironments"
        label="sensitiveEnvironments"
        value={value.sensitiveEnvironments ?? []}
        onChange={(l) => patch((s) => ({ ...s, sensitiveEnvironments: l }))}
      />
    </div>
  );
}

/**
 * Per-policy enforcement mode: enforce (default) or shadow (recorded as a finding, never blocking). Policies come from
 * the catalogue of the loaded bundles; ids not in the catalogue (e.g. a policy of a bundle not loaded yet) stay editable.
 */
export function EnforcementEditor({
  value,
  onChange,
}: {
  value: Record<string, EnforcementMode>;
  onChange: (v: Record<string, EnforcementMode>) => void;
}) {
  const catalog = useCatalog();
  const ids = Array.from(new Set([...(catalog.data ?? []).map((p) => p.policyId), ...Object.keys(value ?? {})])).sort();
  const shadowCount = Object.values(value ?? {}).filter((m) => m === 'shadow').length;
  const set = (id: string, mode: EnforcementMode) => {
    const next = Object.fromEntries(Object.entries(value ?? {}).filter(([k]) => k !== id)) as Record<string, EnforcementMode>;
    if (mode === 'shadow') next[id] = mode;
    onChange(next);
  };
  return (
    <div className="flex flex-col gap-3">
      <p className="text-xs text-muted">
        A policy in <strong>shadow</strong> mode is still evaluated and its findings are recorded (Shadow findings page,
        metric governance_shadow_findings_total), but it never denies and never opens an approval. Only policies set to
        shadow are stored; everything else enforces.{' '}
        {shadowCount > 0 && <Badge tone="warn">{shadowCount} in shadow</Badge>}
      </p>
      {catalog.isError && (
        <p className="text-xs text-warn">Catalogue unavailable: only policies already listed in this version are shown.</p>
      )}
      {ids.length === 0 ? (
        <p className="text-xs text-muted">No policies known.</p>
      ) : (
        <div className="grid gap-2 sm:grid-cols-2">
          {ids.map((id) => {
            const entry = (catalog.data ?? []).find((p) => p.policyId === id);
            const mode: EnforcementMode = value?.[id] === 'shadow' ? 'shadow' : 'enforce';
            return (
              <div key={id} className="flex items-center justify-between gap-3 rounded-md border border-border px-3 py-2">
                <div className="min-w-0">
                  <label
                    htmlFor={`enforcement-${id}`}
                    className="mono block overflow-hidden text-ellipsis whitespace-nowrap text-xs font-medium"
                    title={entry?.description}
                  >
                    {id}
                  </label>
                  <p className="text-xs text-muted">
                    {entry ? `${entry.bundle ?? '?'} · ${entry.kind === 'APPROVAL' ? 'approval' : entry.severity.toLowerCase()}` : 'not in the loaded catalogue'}
                  </p>
                </div>
                <Select
                  id={`enforcement-${id}`}
                  className="w-32"
                  value={mode}
                  onChange={(e) => set(id, e.target.value as EnforcementMode)}
                >
                  <option value="enforce">enforce</option>
                  <option value="shadow">shadow</option>
                </Select>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
