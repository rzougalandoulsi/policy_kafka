import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { gzipSync } from 'fflate';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { renderApp } from '@/test/render';
import { ADMIN_TOKEN, bundles } from '@/test/fixtures';
import { UploadBundleDialog, bundleNameOf, validateInspection } from './UploadBundleDialog';

function tarFile(name: string, content: string): Uint8Array[] {
  const enc = new TextEncoder();
  const header = new Uint8Array(512);
  header.set(enc.encode(name), 0);
  header.set(enc.encode(content.length.toString(8).padStart(11, '0') + '\0'), 124);
  header[156] = '0'.charCodeAt(0);
  const body = new Uint8Array(Math.ceil(content.length / 512) * 512);
  body.set(enc.encode(content));
  return [header, body];
}

function tarGz(files: Array<[string, string]>): Uint8Array {
  const parts = [...files.flatMap(([n, c]) => tarFile(n, c)), new Uint8Array(1024)];
  const buf = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    buf.set(p, o);
    o += p.length;
  }
  return gzipSync(buf);
}

const GOOD_BUNDLE = tarGz([
  ['.manifest', '{"revision":"core-r42","roots":["governance/core"]}'],
  ['governance/core/core.rego', 'package governance.core'],
]);
const NAMING_BUNDLE = tarGz([
  ['.manifest', '{"revision":"naming-r42","roots":["governance/naming"]}'],
  ['governance/naming/naming.rego', 'package governance.naming'],
]);
const GOOD_REPORT = JSON.stringify([
  { package: 'data.governance.a', name: 'test_one' },
  { package: 'data.governance.a', name: 'test_two' },
  { package: 'data.governance.b', name: 'test_three' },
]);

function file(name: string, data: Uint8Array | string, type: string) {
  return new File([data as BlobPart], name, { type });
}

describe('UploadBundleDialog', () => {
  it('rejects a test report that is not an array', async () => {
    const user = userEvent.setup({ applyAccept: false });
    renderApp(<UploadBundleDialog open onOpenChange={() => {}} />, { token: ADMIN_TOKEN });
    await user.upload(
      screen.getByLabelText(/Test report/),
      file('report.json', '{"not":"an array"}', 'application/json'),
    );
    expect(await screen.findByText(/Invalid test report/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Upload' })).toBeDisabled();
  });

  it('flags failing tests the server would reject', async () => {
    const user = userEvent.setup({ applyAccept: false });
    renderApp(<UploadBundleDialog open onOpenChange={() => {}} />, { token: ADMIN_TOKEN });
    const report = JSON.stringify([{ package: 'data.governance.a', name: 'test_bad', fail: true }]);
    await user.upload(screen.getByLabelText(/Test report/), file('report.json', report, 'application/json'));
    expect(
      await screen.findByText(/failing or skipped tests.*data.governance.a.test_bad/),
    ).toBeInTheDocument();
  });

  it('inspects a valid bundle, counts tests, and uploads both', async () => {
    const user = userEvent.setup({ applyAccept: false });
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post('/api/v1/admin/policy-bundles', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...bundles[1], id: 'bundle-new', revision: 'r42', status: 'UPLOADED' });
      }),
    );
    const onOpenChange = vi.fn();
    renderApp(<UploadBundleDialog open onOpenChange={onOpenChange} />, { token: ADMIN_TOKEN });
    await user.upload(
      screen.getByLabelText(/Bundles \(tar.gz\)/),
      file('core-bundle.tar.gz', GOOD_BUNDLE, 'application/gzip'),
    );
    const items = await screen.findByTestId('bundle-items');
    expect(items).toHaveTextContent('core');
    expect(items).toHaveTextContent('core-r42');
    expect(items).toHaveTextContent('root governance/core');
    expect(items).toHaveTextContent('1 rego file');
    await user.upload(
      screen.getByLabelText(/Test report/),
      file('test-report.json', GOOD_REPORT, 'application/json'),
    );
    expect(await screen.findByText('3 tests, all passing.')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Comment'), 'rollout r42');
    await user.click(screen.getByRole('button', { name: 'Upload' }));
    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.testReportJson).toBe(GOOD_REPORT);
    expect(body!.comment).toBe('rollout r42');
    expect(atob(body!.bundleBase64 as string).length).toBe(GOOD_BUNDLE.length);
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
  });

  it('refuses archives that are not bundles', async () => {
    const user = userEvent.setup({ applyAccept: false });
    renderApp(<UploadBundleDialog open onOpenChange={() => {}} />, { token: ADMIN_TOKEN });
    await user.upload(
      screen.getByLabelText(/Bundles \(tar.gz\)/),
      file('x.tar.gz', 'definitely not gzip', 'application/gzip'),
    );
    expect(await screen.findByText(/Not a valid gzip tar archive/)).toBeInTheDocument();
  });

  it('uploads several bundles sequentially with one shared report and offers release activation only for a full set', async () => {
    const user = userEvent.setup({ applyAccept: false });
    const uploaded: string[] = [];
    server.use(
      http.post('/api/v1/admin/policy-bundles', async ({ request }) => {
        const body = (await request.json()) as { bundleBase64: string; testReportJson: string };
        const name = atob(body.bundleBase64).length === GOOD_BUNDLE.length ? 'core' : 'naming';
        uploaded.push(name + ':' + body.testReportJson.length);
        return HttpResponse.json({
          ...bundles[1],
          id: `bundle-new-${name}`,
          bundleName: name,
          revision: `${name}-r42`,
          status: 'UPLOADED',
        });
      }),
    );
    const onOpenChange = vi.fn();
    renderApp(<UploadBundleDialog open onOpenChange={onOpenChange} />, { token: ADMIN_TOKEN });
    await user.upload(screen.getByLabelText(/Bundles \(tar.gz\)/), [
      file('core-bundle.tar.gz', GOOD_BUNDLE, 'application/gzip'),
      file('naming-bundle.tar.gz', NAMING_BUNDLE, 'application/gzip'),
    ]);
    const items = await screen.findByTestId('bundle-items');
    expect(items).toHaveTextContent('core-r42');
    expect(items).toHaveTextContent('naming-r42');
    expect(screen.getByText(/2 bundles selected/)).toHaveTextContent(
      'manifests, quotas, resources, changes missing',
    );
    await user.upload(
      screen.getByLabelText(/Test report/),
      file('test-report.json', GOOD_REPORT, 'application/json'),
    );
    await screen.findByText('3 tests, all passing.');
    expect(screen.getByRole('button', { name: 'Upload and activate release' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Upload' }));
    await waitFor(() =>
      expect(uploaded).toEqual([`core:${GOOD_REPORT.length}`, `naming:${GOOD_REPORT.length}`]),
    );
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
  });

  it('rejects two files for the same bundle', async () => {
    const user = userEvent.setup({ applyAccept: false });
    renderApp(<UploadBundleDialog open onOpenChange={() => {}} />, { token: ADMIN_TOKEN });
    await user.upload(screen.getByLabelText(/Bundles \(tar.gz\)/), [
      file('a.tar.gz', GOOD_BUNDLE, 'application/gzip'),
      file('b.tar.gz', GOOD_BUNDLE, 'application/gzip'),
    ]);
    expect(await screen.findAllByText(/Two files declare bundle core/)).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Upload' })).toBeDisabled();
  });

  it('mirrors the server-side inspection rules', () => {
    expect(validateInspection({ manifest: null, revision: null, roots: [], regoFiles: [] })).toMatch(
      /no \.manifest/,
    );
    expect(validateInspection({ manifest: {}, revision: null, roots: [], regoFiles: ['a.rego'] })).toMatch(
      /declare a revision/,
    );
    expect(
      validateInspection({
        manifest: { revision: 'x' },
        revision: 'x',
        roots: ['governance'],
        regoFiles: ['a.rego'],
      }),
    ).toMatch(/exactly one root governance\/<bundle>/);
    expect(
      validateInspection({
        manifest: { revision: 'x' },
        revision: 'x',
        roots: ['governance/core', 'governance/naming'],
        regoFiles: ['a.rego'],
      }),
    ).toMatch(/exactly one root/);
    expect(
      validateInspection({
        manifest: { revision: 'x' },
        revision: 'x',
        roots: ['governance/core'],
        regoFiles: [],
      }),
    ).toMatch(/no \.rego/);
    expect(
      validateInspection({
        manifest: { revision: 'x' },
        revision: 'x',
        roots: ['governance/quotas'],
        regoFiles: ['a.rego'],
      }),
    ).toBeNull();
    expect(bundleNameOf(['governance/changes'])).toBe('changes');
    expect(bundleNameOf(['governance/other'])).toBeNull();
    expect(bundleNameOf(['governance'])).toBeNull();
  });
});
