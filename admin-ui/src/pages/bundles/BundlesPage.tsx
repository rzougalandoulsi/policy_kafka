import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import { Layers, Upload, Undo2 } from 'lucide-react';
import { describeError } from '@/api/client';
import {
  useActivateBundle,
  useActivateRelease,
  useBundles,
  useDiagnostics,
  useRollbackBundle,
} from '@/api/hooks';
import { BUNDLE_NAMES, type PolicyBundleVersion, type RevisionDiagnostics } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import { AdminOnly } from '@/auth/guards';
import { Badge, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardHeader } from '@/components/ui/card';
import { ConfirmDialog } from '@/components/ui/confirm-dialog';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Field, Select } from '@/components/ui/form';
import { PageHeader } from '@/components/ui/page';
import { Spinner } from '@/components/ui/spinner';
import { EmptyState, ErrorState, InlineAlert } from '@/components/ui/states';
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table';
import { formatInstant } from '@/lib/time';
import { shortSha } from '@/lib/utils';
import { ActivationWatcher } from './ActivationWatcher';
import type { ActivationTarget } from './activation';
import { DiagnosticsBanner } from './DiagnosticsBanner';
import { UploadBundleDialog } from './UploadBundleDialog';

/** Versions per bundle name, newest upload first, in the canonical bundle order (unknown names last). */
export function groupByBundle(versions: PolicyBundleVersion[]): Array<[string, PolicyBundleVersion[]]> {
  const byName = new Map<string, PolicyBundleVersion[]>();
  for (const v of versions) byName.set(v.bundleName, [...(byName.get(v.bundleName) ?? []), v]);
  const names = [
    ...BUNDLE_NAMES,
    ...[...byName.keys()].filter((n) => !(BUNDLE_NAMES as readonly string[]).includes(n)),
  ];
  return names
    .filter((n) => byName.has(n))
    .map((n) => [n, [...byName.get(n)!].sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt))]);
}

export function latestRetired(
  versions: PolicyBundleVersion[],
  bundleName: string,
): PolicyBundleVersion | undefined {
  return versions
    .filter((b) => b.bundleName === bundleName && b.status === 'RETIRED' && b.retiredAt)
    .sort((a, b) => (b.retiredAt ?? '').localeCompare(a.retiredAt ?? ''))[0];
}

/** Default pick for "Activate release": the newest UPLOADED version per bundle, else the ACTIVE one. */
export function defaultReleaseSelection(versions: PolicyBundleVersion[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [name, list] of groupByBundle(versions)) {
    const uploaded = list.find((v) => v.status === 'UPLOADED');
    const active = list.find((v) => v.status === 'ACTIVE');
    const pick = uploaded ?? active;
    if (pick) out[name] = pick.id;
  }
  return out;
}

export function BundlesPage() {
  const { config } = useAuth();
  const localAdmin = config.localPolicyAdmin;
  const bundles = useBundles();
  const diagnostics = useDiagnostics();
  const activate = useActivateBundle();
  const rollback = useRollbackBundle();
  const [uploadOpen, setUploadOpen] = useState(false);
  const [releaseOpen, setReleaseOpen] = useState(false);
  const [activateTarget, setActivateTarget] = useState<PolicyBundleVersion | null>(null);
  const [rollbackBundle, setRollbackBundle] = useState<string | null>(null);
  const [watching, setWatching] = useState<ActivationTarget | null>(null);

  const versions = useMemo(() => bundles.data ?? [], [bundles.data]);
  const groups = useMemo(() => groupByBundle(versions), [versions]);
  const activeOf = (name: string) => versions.find((v) => v.bundleName === name && v.status === 'ACTIVE');
  const rollbackTarget = rollbackBundle ? latestRetired(versions, rollbackBundle) : undefined;

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Policy bundles"
        description={
          localAdmin
            ? 'Six independently versioned Rego bundles, one per root governance/<name>. The ACTIVE version of each forms the policy release OPA must run; every decision records the release id and the revision OPA reported per bundle. Activation is verified against OPA and a version OPA does not adopt within 90 seconds is reverted automatically.'
            : 'Six independently versioned Rego bundles, one per root governance/<name>. In this environment the policy release is pinned by the mounted release manifest and OPA pulls the signed revisions from Artifactory; every decision records the release id and the revision OPA reported per bundle.'
        }
        actions={
          localAdmin ? (
            <AdminOnly>
              <Button variant="secondary" onClick={() => setReleaseOpen(true)} disabled={versions.length === 0}>
                <Layers className="h-4 w-4" aria-hidden /> Activate release
              </Button>
              <Button onClick={() => setUploadOpen(true)}>
                <Upload className="h-4 w-4" aria-hidden /> Upload bundles
              </Button>
            </AdminOnly>
          ) : undefined
        }
      />
      <DiagnosticsBanner />
      {!localAdmin && (
        <div data-testid="release-mode-notice">
          <InlineAlert tone="info">
            Policies are managed in the governance-policies repository: a change reaches this environment through a
            promoted release (Workflows B and C), never through this console. Upload, activation and rollback are
            disabled here and the API answers <code>409 POLICY_SOURCE_IS_GIT</code>.
          </InlineAlert>
        </div>
      )}
      {watching && <ActivationWatcher target={watching} onDismiss={() => setWatching(null)} />}

      <ReleaseCard diagnostics={diagnostics.data} />

      {bundles.isPending ? (
        <Card>
          <Spinner label="Loading bundles" className="p-6" />
        </Card>
      ) : bundles.isError ? (
        <Card>
          <ErrorState error={bundles.error} onRetry={() => bundles.refetch()} />
        </Card>
      ) : versions.length === 0 ? (
        localAdmin ? (
          <Card>
            <EmptyState
              title="No bundles uploaded"
              description="Build them with policies/build.sh and upload them here, or run deploy/scripts/upload-release.sh."
            />
          </Card>
        ) : null
      ) : (
        groups.map(([name, list]) => {
          const active = list.find((v) => v.status === 'ACTIVE');
          const diag = diagnostics.data?.bundles[name];
          return (
            <Card key={name} data-testid={`bundle-group-${name}`}>
              <CardHeader
                title={<span className="mono">{name}</span>}
                description={
                  <span className="flex flex-wrap items-center gap-2">
                    {active ? (
                      <>
                        active <code>{active.revision}</code>
                      </>
                    ) : (
                      <Badge tone="warn">no active version</Badge>
                    )}
                    {diag && active && (
                      <>
                        <span>· OPA reports {diag.loaded ?? 'nothing'}</span>
                        <Badge tone={diag.match ? 'ok' : 'danger'}>
                          {diag.match ? 'in sync' : 'mismatch'}
                        </Badge>
                      </>
                    )}
                  </span>
                }
                actions={
                  localAdmin && (
                  <AdminOnly>
                    <Button
                      size="sm"
                      variant="secondary"
                      onClick={() => setRollbackBundle(name)}
                      disabled={!latestRetired(versions, name)}
                      title={
                        latestRetired(versions, name)
                          ? undefined
                          : `No retired ${name} version to roll back to`
                      }
                    >
                      <Undo2 className="h-4 w-4" aria-hidden /> Rollback
                    </Button>
                  </AdminOnly>
                  )
                }
              />
              <Table>
                <THead>
                  <TR>
                    <TH>Revision</TH>
                    <TH>Status</TH>
                    <TH>SHA-256</TH>
                    <TH>Tests</TH>
                    <TH>Comment</TH>
                    <TH>Uploaded</TH>
                    <TH>Activated</TH>
                    <TH>Failure</TH>
                    <TH>
                      <span className="sr-only">Actions</span>
                    </TH>
                  </TR>
                </THead>
                <TBody>
                  {list.map((b) => (
                    <TR key={b.id} data-testid={`bundle-row-${b.revision}`}>
                      <TD className="mono font-medium">{b.revision}</TD>
                      <TD>
                        <StatusBadge status={b.status} />
                      </TD>
                      <TD className="mono text-xs" title={b.sha256}>
                        {shortSha(b.sha256)}
                      </TD>
                      <TD>
                        {b.testReport?.passed != null ? (
                          <Badge tone="ok">{b.testReport.passed} passed</Badge>
                        ) : (
                          <Badge tone="warn">none</Badge>
                        )}
                      </TD>
                      <TD className="max-w-xs truncate" title={b.comment ?? undefined}>
                        {b.comment ?? '—'}
                      </TD>
                      <TD>
                        <div>{formatInstant(b.uploadedAt)}</div>
                        <div className="text-xs text-muted">{b.uploadedBy}</div>
                      </TD>
                      <TD>{formatInstant(b.activatedAt)}</TD>
                      <TD className="max-w-xs text-xs text-danger">{b.failureReason ?? ''}</TD>
                      <TD className="text-right">
                        {localAdmin && (
                          <AdminOnly>
                            {(b.status === 'UPLOADED' || b.status === 'RETIRED') && (
                              <Button size="sm" variant="secondary" onClick={() => setActivateTarget(b)}>
                                Activate
                              </Button>
                            )}
                          </AdminOnly>
                        )}
                      </TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            </Card>
          );
        })
      )}

      <UploadBundleDialog
        open={uploadOpen}
        onOpenChange={setUploadOpen}
        onActivated={(target) => setWatching(target)}
      />

      <ReleaseDialog
        open={releaseOpen}
        onOpenChange={setReleaseOpen}
        versions={versions}
        onActivated={(target) => setWatching(target)}
      />

      <ConfirmDialog
        open={!!activateTarget}
        onOpenChange={(o) => !o && setActivateTarget(null)}
        title={`Activate ${activateTarget?.bundleName ?? ''} revision ${activateTarget?.revision ?? ''}?`}
        confirmLabel="Activate"
        onConfirm={async () => {
          if (!activateTarget) return;
          try {
            const v = await activate.mutateAsync(activateTarget.id);
            toast.success(`Activated ${v.bundleName} revision ${v.revision}; waiting for OPA`);
            setWatching({ bundleName: v.bundleName, revision: v.revision });
          } catch (e) {
            toast.error(describeError(e));
            throw e;
          }
        }}
      >
        <p className="text-sm">
          {activateTarget && activeOf(activateTarget.bundleName) ? (
            <>
              The current ACTIVE <code>{activateTarget.bundleName}</code> revision{' '}
              <code>{activeOf(activateTarget.bundleName)?.revision}</code> will be retired.{' '}
            </>
          ) : (
            'No version of this bundle is active at the moment. '
          )}
          Only this bundle changes; the other five keep their active revision, so the release id changes. OPA
          polls the bundle endpoint and must adopt <code>{activateTarget?.revision}</code> within 90 seconds;
          otherwise Governance marks it FAILED and restores the previous version automatically.
        </p>
      </ConfirmDialog>

      <ConfirmDialog
        open={!!rollbackBundle}
        onOpenChange={(o) => !o && setRollbackBundle(null)}
        title={`Roll back ${rollbackBundle ?? ''} to its previous version?`}
        confirmLabel="Roll back"
        tone="danger"
        disabled={!rollbackTarget}
        onConfirm={async () => {
          if (!rollbackBundle) return;
          try {
            const v = await rollback.mutateAsync(rollbackBundle);
            toast.success(`Rolled back ${v.bundleName} to revision ${v.revision}; waiting for OPA`);
            setWatching({ bundleName: v.bundleName, revision: v.revision });
          } catch (e) {
            toast.error(describeError(e));
            throw e;
          }
        }}
      >
        <p className="text-sm">
          {rollbackTarget && rollbackBundle ? (
            <>
              Re-activates the most recently retired <code>{rollbackBundle}</code> revision{' '}
              <code>{rollbackTarget.revision}</code> (retired {formatInstant(rollbackTarget.retiredAt)}) and
              retires <code>{activeOf(rollbackBundle)?.revision ?? 'the current version'}</code>. The other
              bundles are untouched. OPA adoption is verified the same way as an activation.
            </>
          ) : (
            'There is no retired version of this bundle to roll back to.'
          )}
        </p>
      </ConfirmDialog>
    </div>
  );
}

function ReleaseCard({ diagnostics }: { diagnostics: RevisionDiagnostics | undefined }) {
  if (!diagnostics) return null;
  return (
    <Card data-testid="release-card">
      <CardHeader
        title="Policy release"
        description={
          diagnostics.mode === 'release'
            ? `Pinned by the mounted release manifest (${diagnostics.source ?? 'release file'}). Governance fails closed unless OPA runs exactly this set and the core bundle reports the composition ready.`
            : 'One ACTIVE version per bundle. Governance fails closed unless OPA runs exactly this set and the core bundle reports the composition ready.'
        }
        actions={
          <>
            {diagnostics.releaseId ? (
              <code className="text-sm font-semibold">{diagnostics.releaseId}</code>
            ) : (
              <Badge tone="warn">no release</Badge>
            )}
            <Badge tone={diagnostics.match ? 'ok' : 'danger'}>
              {diagnostics.match ? 'in sync' : 'mismatch'}
            </Badge>
            <Badge tone={diagnostics.ready ? 'ok' : 'danger'}>
              {diagnostics.ready ? 'ready' : 'not ready'}
            </Badge>
          </>
        }
      />
      <Table>
        <THead>
          <TR>
            <TH>Bundle</TH>
            <TH>{diagnostics.mode === 'release' ? 'Expected (release manifest)' : 'Expected (ACTIVE)'}</TH>
            <TH>OPA reports</TH>
            <TH>State</TH>
          </TR>
        </THead>
        <TBody>
          {BUNDLE_NAMES.map((name) => {
            const b = diagnostics.bundles[name];
            return (
              <TR key={name} data-testid={`release-row-${name}`}>
                <TD className="mono font-medium">{name}</TD>
                <TD className="mono text-xs">{b?.expected ?? '—'}</TD>
                <TD className="mono text-xs">{b?.loaded ?? 'nothing'}</TD>
                <TD>
                  {!b?.expected ? (
                    <Badge tone="warn">{diagnostics.mode === 'release' ? 'not in manifest' : 'no active version'}</Badge>
                  ) : (
                    <Badge tone={b.match ? 'ok' : 'danger'}>{b.match ? 'in sync' : 'mismatch'}</Badge>
                  )}
                </TD>
              </TR>
            );
          })}
        </TBody>
      </Table>
    </Card>
  );
}

function ReleaseDialog({
  open,
  onOpenChange,
  versions,
  onActivated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  versions: PolicyBundleVersion[];
  onActivated: (target: ActivationTarget) => void;
}) {
  const activateRelease = useActivateRelease();
  const [selection, setSelection] = useState<Record<string, string> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const groups = useMemo(() => groupByBundle(versions), [versions]);
  const current = selection ?? defaultReleaseSelection(versions);
  const missing = BUNDLE_NAMES.filter((n) => !current[n]);
  const changed = BUNDLE_NAMES.filter((n) => {
    const chosen = versions.find((v) => v.id === current[n]);
    return chosen && chosen.status !== 'ACTIVE';
  });

  const submit = async () => {
    setError(null);
    try {
      const release = await activateRelease.mutateAsync(BUNDLE_NAMES.map((n) => current[n]!));
      toast.success(`Activated release ${release.id}; waiting for OPA`);
      const first = changed[0];
      if (first) onActivated({ bundleName: first, revision: release.revisions[first]! });
      setSelection(null);
      onOpenChange(false);
    } catch (e) {
      setError(describeError(e));
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (activateRelease.isPending) return;
        if (!o) {
          setSelection(null);
          setError(null);
        }
        onOpenChange(o);
      }}
    >
      <DialogContent
        title="Activate a policy release"
        description="Pick one version per bundle. All six switch in one transaction; OPA then adopts each bundle on its own poll, and decisions fail closed until the whole set matches."
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => onOpenChange(false)}
              disabled={activateRelease.isPending}
            >
              Cancel
            </Button>
            <Button
              onClick={() => void submit()}
              disabled={missing.length > 0 || changed.length === 0}
              loading={activateRelease.isPending}
              title={
                missing.length > 0
                  ? `No version available for ${missing.join(', ')}`
                  : changed.length === 0
                    ? 'Every selected version is already active'
                    : undefined
              }
            >
              Activate release
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          {groups.map(([name, list]) => {
            const options = list.filter((v) => v.status !== 'FAILED');
            return (
              <Field key={name} id={`release-${name}`} label={name}>
                <Select
                  id={`release-${name}`}
                  value={current[name] ?? ''}
                  onChange={(e) => setSelection({ ...current, [name]: e.target.value })}
                  disabled={options.length === 0}
                >
                  {options.length === 0 && <option value="">no usable version</option>}
                  {options.map((v) => (
                    <option key={v.id} value={v.id}>
                      {v.revision} · {v.status.toLowerCase()}
                      {v.comment ? ` · ${v.comment}` : ''}
                    </option>
                  ))}
                </Select>
              </Field>
            );
          })}
          {missing.length > 0 && (
            <InlineAlert tone="warn">
              Upload a version of <code>{missing.join(', ')}</code> first: a release needs all six bundles.
            </InlineAlert>
          )}
          {changed.length > 0 && (
            <p className="text-xs text-muted">
              Changes: <code>{changed.join(', ')}</code>; the other bundles keep their active revision.
            </p>
          )}
          {error && <InlineAlert tone="danger">{error}</InlineAlert>}
        </div>
      </DialogContent>
    </Dialog>
  );
}
