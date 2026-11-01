import { useState } from 'react';
import { toast } from 'sonner';
import { describeError } from '@/api/client';
import { useActivateBundle, useActivateRelease, useUploadBundle } from '@/api/hooks';
import { BUNDLE_NAMES, type PolicyBundleVersion } from '@/api/types';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Field, Input, Textarea, fieldProps } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/states';
import {
  inspectBundle,
  summarizeTestReport,
  toBase64,
  type BundleInspection,
  type TestReportSummary,
} from '@/lib/bundle';
import type { ActivationTarget } from './activation';

const WARN_BYTES = 6 * 1024 * 1024;

function readFile(file: File, as: 'arrayBuffer' | 'text'): Promise<ArrayBuffer | string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result as ArrayBuffer | string);
    r.onerror = () => reject(r.error ?? new Error('Could not read file'));
    if (as === 'arrayBuffer') r.readAsArrayBuffer(file);
    else r.readAsText(file);
  });
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
}

/** Bundle name declared by the single root governance/<name>, or null when the roots are not of that shape. */
export function bundleNameOf(roots: string[]): string | null {
  if (roots.length !== 1 || !roots[0]!.startsWith('governance/')) return null;
  const name = roots[0]!.slice('governance/'.length);
  return (BUNDLE_NAMES as readonly string[]).includes(name) ? name : null;
}

/** Mirrors PolicyBundleService.inspect so operators learn about a bad artifact before uploading it. */
export function validateInspection(i: BundleInspection): string | null {
  if (!i.manifest) return 'Bundle has no .manifest (build it with `opa build -r <revision>`).';
  if (!i.revision || i.revision === 'null') return 'Bundle .manifest must declare a revision.';
  if (i.regoFiles.length === 0) return 'Bundle contains no .rego files.';
  if (!bundleNameOf(i.roots))
    return `Bundle must declare exactly one root governance/<bundle> (${BUNDLE_NAMES.join(', ')}), got [${i.roots.join(', ')}].`;
  return null;
}

interface Item {
  fileName: string;
  bytes: Uint8Array | null;
  inspection: BundleInspection | null;
  bundleName: string | null;
  error: string | null;
}

export function UploadBundleDialog({
  open,
  onOpenChange,
  onActivated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Called after "Upload and activate" succeeds so the page can watch OPA adopt the (first changed) bundle. */
  onActivated?: (target: ActivationTarget) => void;
}) {
  const upload = useUploadBundle();
  const activate = useActivateBundle();
  const activateRelease = useActivateRelease();
  const [items, setItems] = useState<Item[]>([]);
  const [reportText, setReportText] = useState<string | null>(null);
  const [reportSummary, setReportSummary] = useState<TestReportSummary | null>(null);
  const [reportError, setReportError] = useState<string | null>(null);
  const [comment, setComment] = useState('');
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState<'upload' | 'activate' | null>(null);
  const [progress, setProgress] = useState<string | null>(null);

  const reset = () => {
    setItems([]);
    setReportText(null);
    setReportSummary(null);
    setReportError(null);
    setComment('');
    setServerError(null);
    setBusy(null);
    setProgress(null);
  };

  const onBundleFiles = async (files: FileList | null) => {
    setItems([]);
    if (!files || files.length === 0) return;
    const next: Item[] = [];
    for (const file of Array.from(files)) {
      try {
        const bytes = new Uint8Array((await readFile(file, 'arrayBuffer')) as ArrayBuffer);
        const inspection = inspectBundle(bytes);
        const problem = validateInspection(inspection);
        next.push({
          fileName: file.name,
          bytes: problem ? null : bytes,
          inspection,
          bundleName: bundleNameOf(inspection.roots),
          error: problem,
        });
      } catch (e) {
        next.push({
          fileName: file.name,
          bytes: null,
          inspection: null,
          bundleName: null,
          error: `Not a valid gzip tar archive: ${e instanceof Error ? e.message : String(e)}`,
        });
      }
    }
    const names = next.map((i) => i.bundleName).filter((n): n is string => !!n);
    const dup = names.find((n, i) => names.indexOf(n) !== i);
    if (dup)
      next.forEach((i) => (i.error = i.bundleName === dup ? `Two files declare bundle ${dup}.` : i.error));
    setItems(next);
  };

  const onReportFile = async (file: File | undefined) => {
    setReportText(null);
    setReportSummary(null);
    setReportError(null);
    if (!file) return;
    try {
      const text = (await readFile(file, 'text')) as string;
      const summary = summarizeTestReport(text);
      setReportSummary(summary);
      if (summary.total === 0) setReportError('Test report contains no tests.');
      else if (summary.failed.length)
        setReportError(
          `Report has failing or skipped tests (the server rejects it): ${summary.failed.join(', ')}`,
        );
      else setReportText(text);
    } catch (e) {
      setReportError(`Invalid test report: ${e instanceof Error ? e.message : String(e)}`);
    }
  };

  const valid = items.filter((i) => i.bytes && !i.error);
  const problems = items.filter((i) => i.error).length;
  const bundleError =
    problems === 0
      ? null
      : problems === 1
        ? 'One selected file is not usable (see below).'
        : `${problems} selected files are not usable (see below).`;
  const totalBytes = valid.reduce((n, i) => n + (i.bytes?.length ?? 0), 0) + (reportText?.length ?? 0);
  const ready = valid.length > 0 && items.every((i) => !i.error) && !!reportText && !busy;
  const coversRelease = BUNDLE_NAMES.every((n) => valid.some((i) => i.bundleName === n));
  const canActivate = ready && (valid.length === 1 || coversRelease);
  const activateLabel = valid.length > 1 ? 'Upload and activate release' : 'Upload and activate';

  const submit = async (andActivate: boolean) => {
    if (!ready || !reportText) return;
    setServerError(null);
    setBusy(andActivate ? 'activate' : 'upload');
    const created: PolicyBundleVersion[] = [];
    try {
      for (const item of valid) {
        setProgress(`Uploading ${item.bundleName} (${created.length + 1} of ${valid.length})…`);
        const v = await upload.mutateAsync({
          bundleBase64: toBase64(item.bytes!),
          testReportJson: reportText,
          comment: comment.trim() || null,
        });
        created.push(v);
        toast.success(`Uploaded ${v.bundleName} revision ${v.revision}`);
      }
      if (andActivate && created.length === 1) {
        const activated = await activate.mutateAsync(created[0]!.id);
        toast.success(`Activated ${activated.bundleName} revision ${activated.revision}; waiting for OPA`);
        onActivated?.({ bundleName: activated.bundleName, revision: activated.revision });
      } else if (andActivate) {
        setProgress('Activating release…');
        const release = await activateRelease.mutateAsync(created.map((v) => v.id));
        toast.success(`Activated release ${release.id}; waiting for OPA`);
        const first = created[0]!;
        onActivated?.({ bundleName: first.bundleName, revision: first.revision });
      }
      reset();
      onOpenChange(false);
    } catch (e) {
      setServerError(
        (created.length
          ? `Uploaded ${created.map((v) => v.bundleName).join(', ')} before the failure. `
          : '') + describeError(e),
      );
      setBusy(null);
      setProgress(null);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (busy) return;
        if (!o) reset();
        onOpenChange(o);
      }}
    >
      <DialogContent
        title="Upload policy bundles"
        description="Bundles built and tested by policies/build.sh: one or more tar.gz files (one per bundle root) plus the shared opa test JSON report. Raw Rego is never accepted."
        footer={
          <>
            <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={!!busy}>
              Cancel
            </Button>
            <Button
              variant="secondary"
              onClick={() => submit(false)}
              disabled={!ready}
              loading={busy === 'upload'}
            >
              Upload
            </Button>
            <Button
              onClick={() => submit(true)}
              disabled={!canActivate}
              loading={busy === 'activate'}
              title={
                ready && !canActivate
                  ? 'Activation needs either a single bundle or a complete set of all six'
                  : undefined
              }
            >
              {activateLabel}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <Field
            id="bundle-file"
            label="Bundles (tar.gz)"
            required
            error={bundleError ?? undefined}
            hint="Output of `opa build -b bundles/<name> -r <name>-<revision>`; select all six to activate them as a release"
          >
            <Input
              {...fieldProps('bundle-file', bundleError ?? undefined, true)}
              type="file"
              multiple
              accept=".gz,.tgz,application/gzip,application/x-gzip"
              onChange={(e) => void onBundleFiles(e.target.files)}
            />
          </Field>
          {items.length > 0 && (
            <ul className="flex flex-col gap-1 text-sm" data-testid="bundle-items">
              {items.map((i) => (
                <li key={i.fileName} className={i.error ? 'text-danger' : ''}>
                  {i.error ? (
                    <>
                      <span className="mono">{i.fileName}</span>: {i.error}
                    </>
                  ) : (
                    <>
                      <code className="font-semibold">{i.bundleName}</code>: revision{' '}
                      <code>{i.inspection?.revision}</code> (root {i.inspection?.roots[0]}),{' '}
                      {i.inspection?.regoFiles.length} rego file
                      {i.inspection?.regoFiles.length === 1 ? '' : 's'}
                    </>
                  )}
                </li>
              ))}
            </ul>
          )}
          {valid.length > 1 && !coversRelease && items.every((i) => !i.error) && (
            <InlineAlert tone="info">
              {valid.length} bundles selected. Select all six (
              {BUNDLE_NAMES.filter((n) => !valid.some((i) => i.bundleName === n)).join(', ')} missing) to
              activate them together as a release, or upload now and activate per bundle.
            </InlineAlert>
          )}
          <Field
            id="report-file"
            label="Test report (JSON)"
            required
            error={reportError ?? undefined}
            hint="Output of `opa test bundles tests --format=json`; every test must pass"
          >
            <Input
              {...fieldProps('report-file', reportError ?? undefined, true)}
              type="file"
              accept=".json,application/json"
              onChange={(e) => void onReportFile(e.target.files?.[0])}
            />
          </Field>
          {reportSummary && !reportError && (
            <InlineAlert tone="ok">{reportSummary.total} tests, all passing.</InlineAlert>
          )}
          <Field id="bundle-comment" label="Comment" hint="Why this revision is being rolled out">
            <Textarea
              id="bundle-comment"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              rows={2}
            />
          </Field>
          {totalBytes > 0 && (
            <p className="text-xs text-muted">
              Upload size: {formatBytes(totalBytes)} (sent base64-encoded, about{' '}
              {formatBytes(Math.ceil(totalBytes * 1.37))}).
            </p>
          )}
          {totalBytes > WARN_BYTES && (
            <InlineAlert tone="warn">
              This upload is larger than 6 MB. The server accepts bodies up to{' '}
              <code>GOVERNANCE_MAX_BODY</code> (8 MB by default) per bundle.
            </InlineAlert>
          )}
          {progress && <InlineAlert tone="info">{progress}</InlineAlert>}
          {serverError && <InlineAlert tone="danger">{serverError}</InlineAlert>}
        </div>
      </DialogContent>
    </Dialog>
  );
}
