import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';
import { PageHeader } from '@/components/ui/page';
import { Card, CardBody } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/components/ui/badge';
import { KV } from '@/components/ui/kv';
import { JsonView } from '@/components/ui/json-view';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Field, Select } from '@/components/ui/form';
import { ErrorState } from '@/components/ui/states';
import { Spinner } from '@/components/ui/spinner';
import { AdminOnly } from '@/auth/guards';
import { useActivatePolicyConfig, usePolicyConfig, usePolicyConfigs } from '@/api/hooks';
import { describeError } from '@/api/client';
import { formatInstant } from '@/lib/time';
import { ActivateDialog } from './ActivateDialog';
import { ConfigDiff } from './ConfigDiff';
import { SectionsView } from './SectionsView';

export function PolicyConfigDetailPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const { data: cfg, isLoading, error, refetch } = usePolicyConfig(id);
  const { data: all } = usePolicyConfigs();
  const activate = useActivatePolicyConfig();
  const [activateOpen, setActivateOpen] = useState(false);

  const others = useMemo(
    () => (all ?? []).filter((c) => c.id !== id).sort((a, b) => b.version - a.version),
    [all, id],
  );
  const active = (all ?? []).find((c) => c.status === 'ACTIVE') ?? null;
  const defaultCompare = active && active.id !== id ? active.id : (others[0]?.id ?? '');
  const [compareId, setCompareId] = useState<string>('');
  const compare = others.find((c) => c.id === (compareId || defaultCompare)) ?? null;

  if (isLoading) return <Spinner full label="Loading version" />;
  if (error || !cfg)
    return <ErrorState error={error ?? new Error('Not found')} onRetry={() => void refetch()} />;

  return (
    <>
      <PageHeader
        title={
          <span className="flex items-center gap-2">
            Policy configuration v{cfg.version} <StatusBadge status={cfg.status} />
          </span>
        }
        description={cfg.comment || undefined}
        actions={
          <>
            <Button variant="ghost" onClick={() => navigate('/policy-configurations')}>
              All versions
            </Button>
            <AdminOnly>
              <Button
                variant="secondary"
                onClick={() => navigate(`/policy-configurations/new?from=${encodeURIComponent(cfg.id)}`)}
              >
                Create draft from this version
              </Button>
              {cfg.status !== 'ACTIVE' && <Button onClick={() => setActivateOpen(true)}>Activate</Button>}
            </AdminOnly>
          </>
        }
      />
      <Card className="mb-4">
        <CardBody>
          <KV
            items={[
              { k: 'Id', v: cfg.id, mono: true },
              { k: 'Version', v: cfg.version },
              { k: 'Status', v: <StatusBadge status={cfg.status} /> },
              { k: 'Created', v: `${cfg.createdBy} · ${formatInstant(cfg.createdAt)}` },
              { k: 'Activated', v: formatInstant(cfg.activatedAt) },
              { k: 'Retired', v: formatInstant(cfg.retiredAt) },
            ]}
          />
        </CardBody>
      </Card>
      <Tabs defaultValue="sections">
        <TabsList>
          <TabsTrigger value="sections">Sections</TabsTrigger>
          <TabsTrigger value="json">JSON</TabsTrigger>
          <TabsTrigger value="compare">Compare</TabsTrigger>
        </TabsList>
        <TabsContent value="sections" className="pt-4">
          <SectionsView content={cfg.content} />
        </TabsContent>
        <TabsContent value="json" className="pt-4">
          <JsonView value={cfg.content} maxHeight="60vh" />
        </TabsContent>
        <TabsContent value="compare" className="pt-4">
          <Card>
            <CardBody className="flex flex-col gap-4">
              <Field id="compare-with" label="Compare with version" className="max-w-xs">
                <Select
                  id="compare-with"
                  value={compare?.id ?? ''}
                  onChange={(e) => setCompareId(e.target.value)}
                  disabled={!others.length}
                >
                  {others.length === 0 && <option value="">No other versions</option>}
                  {others.map((c) => (
                    <option key={c.id} value={c.id}>
                      v{c.version} ({c.status}){c.comment ? ` — ${c.comment}` : ''}
                    </option>
                  ))}
                </Select>
              </Field>
              {compare ? (
                <>
                  <p className="text-sm text-muted">
                    Left:{' '}
                    <Link
                      to={`/policy-configurations/${compare.id}`}
                      className="text-primary hover:underline"
                    >
                      v{compare.version}
                    </Link>
                    , right: this version (v{cfg.version}).
                  </p>
                  <ConfigDiff
                    before={compare.content}
                    after={cfg.content}
                    beforeLabel={`v${compare.version}`}
                    afterLabel={`v${cfg.version}`}
                  />
                </>
              ) : null}
            </CardBody>
          </Card>
        </TabsContent>
      </Tabs>
      <ActivateDialog
        open={activateOpen}
        onOpenChange={setActivateOpen}
        target={cfg}
        active={active}
        onConfirm={async () => {
          try {
            const r = await activate.mutateAsync(cfg.id);
            toast.success(`Version ${r.version} is now ACTIVE`);
          } catch (e) {
            toast.error(describeError(e));
            throw e;
          }
        }}
      />
    </>
  );
}
