import { useState } from 'react';
import { toast } from 'sonner';
import { ApiError, describeError } from '@/api/client';
import { useActivateBundle, useUploadBundle } from '@/api/hooks';
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

/** Mirrors PolicyBundleService.inspect so operators learn about a bad artifact before uploading it. */
export function validateInspection(i: BundleInspection): string | null {
  if (!i.manifest) return 'Bundle has no .manifest (build it with `opa build -r <revision>`).';
  if (!i.revision || i.revision === 'null') return 'Bundle .manifest must declare a revision.';
  if (i.regoFiles.length === 0) return 'Bundle contains no .rego files.';
  if (i.roots.length > 0 && !i.roots.includes('governance'))
    return `Bundle roots must include "governance", got [${i.roots.join(', ')}].`;
  return null;
}

export function UploadBundleDialog({
  open,
  onOpenChange,
  onActivated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Called with the revision after "Upload and activate" succeeds so the page can watch OPA adopt it. */
  onActivated?: (revision: string) => void;
}) {
  const upload = useUploadBundle();
  const activate = useActivateBundle();
  const [bundleBytes, setBundleBytes] = useState<Uint8Array | null>(null);
  const [inspection, setInspection] = useState<BundleInspection | null>(null);
  const [bundleError, setBundleError] = useState<string | null>(null);
  const [reportText, setReportText] = useState<string | null>(null);
  const [reportSummary, setReportSummary] = useState<TestReportSummary | null>(null);
  const [reportError, setReportError] = useState<string | null>(null);
  const [comment, setComment] = useState('');
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState<'upload' | 'activate' | null>(null);

  const reset = () => {
    setBundleBytes(null);
    setInspection(null);
    setBundleError(null);
    setReportText(null);
    setReportSummary(null);
    setReportError(null);
    setComment('');
    setServerError(null);
    setBusy(null);
  };

  const onBundleFile = async (file: File | undefined) => {
    setBundleBytes(null);
    setInspection(null);
    setBundleError(null);
    if (!file) return;
    try {
      const bytes = new Uint8Array((await readFile(file, 'arrayBuffer')) as ArrayBuffer);
      const i = inspectBundle(bytes);
      const problem = validateInspection(i);
      setInspection(i);
      if (problem) setBundleError(problem);
      else setBundleBytes(bytes);
    } catch (e) {
      setBundleError(`Not a valid gzip tar archive: ${e instanceof Error ? e.message : String(e)}`);
    }
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

  const totalBytes = (bundleBytes?.length ?? 0) + (reportText?.length ?? 0);
  const ready = !!bundleBytes && !!reportText && !busy;

  const submit = async (andActivate: boolean) => {
    if (!bundleBytes || !reportText) return;
    setServerError(null);
    setBusy(andActivate ? 'activate' : 'upload');
    try {
      const created = await upload.mutateAsync({
        bundleBase64: toBase64(bundleBytes),
        testReportJson: reportText,
        comment: comment.trim() || null,
      });
      toast.success(`Uploaded bundle revision ${created.revision}`);
      if (andActivate) {
        const activated = await activate.mutateAsync(created.id);
        toast.success(`Activated revision ${activated.revision}; waiting for OPA`);
        onActivated?.(activated.revision);
      }
      reset();
      onOpenChange(false);
    } catch (e) {
      setServerError(e instanceof ApiError ? describeError(e) : describeError(e));
      setBusy(null);
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
        title="Upload policy bundle"
        description="A bundle built and tested by policies/build.sh: the tar.gz plus its opa test JSON report. Raw Rego is never accepted."
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
            <Button onClick={() => submit(true)} disabled={!ready} loading={busy === 'activate'}>
              Upload and activate
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <Field
            id="bundle-file"
            label="Bundle (tar.gz)"
            required
            error={bundleError ?? undefined}
            hint="Output of `opa build -b governance -r <revision>`"
          >
            <Input
              {...fieldProps('bundle-file', bundleError ?? undefined, true)}
              type="file"
              accept=".gz,.tgz,application/gzip,application/x-gzip"
              onChange={(e) => void onBundleFile(e.target.files?.[0])}
            />
          </Field>
          {inspection && !bundleError && (
            <InlineAlert tone="ok">
              Revision <code className="font-semibold">{inspection.revision}</code>, roots [
              {inspection.roots.join(', ') || 'none'}], {inspection.regoFiles.length} rego file
              {inspection.regoFiles.length === 1 ? '' : 's'}.
            </InlineAlert>
          )}
          <Field
            id="report-file"
            label="Test report (JSON)"
            required
            error={reportError ?? undefined}
            hint="Output of `opa test --format=json`; every test must pass"
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
              <code>GOVERNANCE_MAX_BODY</code> (8 MB by default).
            </InlineAlert>
          )}
          {serverError && <InlineAlert tone="danger">{serverError}</InlineAlert>}
        </div>
      </DialogContent>
    </Dialog>
  );
}
