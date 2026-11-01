import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';
import { PageHeader } from '@/components/ui/page';
import { Card, CardBody, CardHeader } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Field, Input, Textarea, fieldProps } from '@/components/ui/form';
import { ErrorState, InlineAlert } from '@/components/ui/states';
import { Spinner } from '@/components/ui/spinner';
import { ConfirmDialog } from '@/components/ui/confirm-dialog';
import { useActivatePolicyConfig, useCreatePolicyConfig, usePolicyConfigs } from '@/api/hooks';
import { describeError } from '@/api/client';
import { stableStringify } from '@/lib/utils';
import type { PolicyConfigurationContent } from '@/api/types';
import { cloneContent, EMPTY_CONTENT, validateContent } from './configSchema';
import { ConfigDiff } from './ConfigDiff';
import { ApprovalsEditor, DestructiveEditor, EnforcementEditor, LimitsEditor, NamingEditor, RbacEditor } from './SectionEditors';

type Content = PolicyConfigurationContent;

export function NewPolicyConfigPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const from = params.get('from');
  const { data: all, isLoading, error, refetch } = usePolicyConfigs();
  const create = useCreatePolicyConfig();
  const activate = useActivatePolicyConfig();

  const active = useMemo(() => all?.find((c) => c.status === 'ACTIVE') ?? null, [all]);
  const base = useMemo(
    () => (from ? (all?.find((c) => c.id === from) ?? null) : active),
    [all, from, active],
  );

  const [content, setContent] = useState<Content | null>(null);
  const [raw, setRaw] = useState('');
  const [rawError, setRawError] = useState<string | null>(null);
  const [comment, setComment] = useState('');
  const [confirmOpen, setConfirmOpen] = useState(false);

  // Seed once the base version is known.
  useEffect(() => {
    if (content !== null || isLoading) return;
    const seed = base ? cloneContent(base.content) : cloneContent(EMPTY_CONTENT);
    setContent(seed);
    setRaw(stableStringify(seed));
  }, [base, isLoading, content]);

  const update = (fn: (c: Content) => Content) => {
    setContent((c) => {
      const next = fn(c ?? cloneContent(EMPTY_CONTENT));
      setRaw(stableStringify(next));
      setRawError(null);
      return next;
    });
  };
  const patchSection =
    <S extends keyof Content>(section: S) =>
    (fn: (s: Content[S]) => Content[S]) =>
      update((c) => ({ ...c, [section]: fn(c[section]) }));

  const onRawChange = (text: string) => {
    setRaw(text);
    try {
      const parsed = JSON.parse(text) as unknown;
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed))
        throw new Error('must be a JSON object');
      setContent(parsed as Content);
      setRawError(null);
    } catch (e) {
      setRawError(e instanceof SyntaxError ? `Invalid JSON: ${e.message}` : (e as Error).message);
    }
  };

  const validation = useMemo(
    () => (content ? validateContent(content) : { errors: [], warnings: [] }),
    [content],
  );
  const blocked = !!rawError || validation.errors.length > 0 || !content;

  const submit = async (andActivate: boolean) => {
    if (!content) return;
    const created = await create.mutateAsync({ content, comment: comment.trim() || null });
    if (andActivate) {
      const r = await activate.mutateAsync(created.id);
      toast.success(`Version ${r.version} created and ACTIVE`);
    } else toast.success(`Draft version ${created.version} created`);
    navigate(`/policy-configurations/${created.id}`);
  };

  if (isLoading || content === null) return <Spinner full label="Loading configuration" />;
  if (error) return <ErrorState error={error} onRetry={() => void refetch()} />;

  const sectionProps = <S extends keyof Content>(s: S) => ({
    value: content[s] ?? (EMPTY_CONTENT[s] as Content[S]),
    patch: patchSection(s),
  });
  const sectionsOk = (s: keyof Content) =>
    typeof content[s] === 'object' && content[s] !== null && !Array.isArray(content[s]);

  return (
    <>
      <PageHeader
        title="New policy configuration version"
        description={
          base
            ? `Starting from v${base.version} (${base.status})${base.comment ? ` — ${base.comment}` : ''}. The result is saved as a DRAFT; activation is a separate, audited step.`
            : 'No base version found; starting from an empty template.'
        }
        actions={
          <>
            <Button variant="ghost" onClick={() => navigate('/policy-configurations')}>
              Cancel
            </Button>
            <Button
              variant="secondary"
              disabled={blocked}
              loading={create.isPending && !confirmOpen}
              onClick={() => submit(false).catch((e) => toast.error(describeError(e)))}
            >
              Create draft
            </Button>
            <Button disabled={blocked} onClick={() => setConfirmOpen(true)}>
              Create and activate
            </Button>
          </>
        }
      />
      {validation.errors.length > 0 && (
        <div className="mb-4">
          <InlineAlert tone="danger">
            <ul className="list-disc pl-4">
              {validation.errors.map((e) => (
                <li key={e}>{e}</li>
              ))}
            </ul>
          </InlineAlert>
        </div>
      )}
      {validation.warnings.length > 0 && (
        <div className="mb-4">
          <InlineAlert tone="warn">{validation.warnings.join(' · ')}</InlineAlert>
        </div>
      )}
      <div className="grid gap-4 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <Card>
          <CardBody>
            <Field
              id="comment"
              label="Comment"
              hint="Why this version exists; recorded in the audit trail."
              className="mb-4"
            >
              <Input
                id="comment"
                value={comment}
                onChange={(e) => setComment(e.target.value)}
                placeholder="e.g. raise partition cap for team-a load test"
              />
            </Field>
            <Tabs defaultValue="naming">
              <TabsList>
                {(['naming', 'rbac', 'limits', 'destructive', 'approvals'] as const).map((s) => (
                  <TabsTrigger key={s} value={s} className={!sectionsOk(s) ? 'text-danger' : undefined}>
                    {s}
                  </TabsTrigger>
                ))}
                <TabsTrigger value="enforcement">
                  enforcement
                  {content.enforcement && Object.values(content.enforcement).some((m) => m === 'shadow') ? ' (shadow)' : ''}
                </TabsTrigger>
                <TabsTrigger value="json">Raw JSON</TabsTrigger>
              </TabsList>
              <TabsContent value="naming" className="pt-4">
                {sectionsOk('naming') ? (
                  <NamingEditor {...sectionProps('naming')} />
                ) : (
                  <MissingSection
                    name="naming"
                    onFix={() => update((c) => ({ ...c, naming: EMPTY_CONTENT.naming }))}
                  />
                )}
              </TabsContent>
              <TabsContent value="rbac" className="pt-4">
                {sectionsOk('rbac') ? (
                  <RbacEditor {...sectionProps('rbac')} />
                ) : (
                  <MissingSection
                    name="rbac"
                    onFix={() => update((c) => ({ ...c, rbac: EMPTY_CONTENT.rbac }))}
                  />
                )}
              </TabsContent>
              <TabsContent value="limits" className="pt-4">
                {sectionsOk('limits') ? (
                  <LimitsEditor {...sectionProps('limits')} />
                ) : (
                  <MissingSection
                    name="limits"
                    onFix={() => update((c) => ({ ...c, limits: EMPTY_CONTENT.limits }))}
                  />
                )}
              </TabsContent>
              <TabsContent value="destructive" className="pt-4">
                {sectionsOk('destructive') ? (
                  <DestructiveEditor {...sectionProps('destructive')} />
                ) : (
                  <MissingSection
                    name="destructive"
                    onFix={() => update((c) => ({ ...c, destructive: EMPTY_CONTENT.destructive }))}
                  />
                )}
              </TabsContent>
              <TabsContent value="approvals" className="pt-4">
                {sectionsOk('approvals') ? (
                  <ApprovalsEditor {...sectionProps('approvals')} />
                ) : (
                  <MissingSection
                    name="approvals"
                    onFix={() => update((c) => ({ ...c, approvals: EMPTY_CONTENT.approvals }))}
                  />
                )}
              </TabsContent>
              <TabsContent value="enforcement" className="pt-4">
                <EnforcementEditor
                  value={content.enforcement ?? {}}
                  onChange={(m) =>
                    update((c) => {
                      const { enforcement: _previous, ...rest } = c;
                      return Object.keys(m).length === 0 ? (rest as Content) : { ...rest, enforcement: m };
                    })
                  }
                />
              </TabsContent>
              <TabsContent value="json" className="pt-4">
                <Field
                  id="raw-json"
                  label="Configuration JSON"
                  error={rawError ?? undefined}
                  hint="Unknown keys are kept as-is and passed to OPA."
                >
                  <Textarea
                    {...fieldProps('raw-json', rawError ?? undefined, true)}
                    value={raw}
                    onChange={(e) => onRawChange(e.target.value)}
                    rows={28}
                    spellCheck={false}
                    className="mono text-xs"
                  />
                </Field>
              </TabsContent>
            </Tabs>
          </CardBody>
        </Card>
        <Card className="self-start">
          <CardHeader
            title="Changes vs. active"
            description={active ? `Compared with ACTIVE v${active.version}` : 'No ACTIVE version'}
          />
          <CardBody>
            {active ? (
              <ConfigDiff
                before={active.content}
                after={content}
                beforeLabel={`v${active.version}`}
                afterLabel="new"
              />
            ) : (
              <p className="text-sm text-muted">Nothing to compare.</p>
            )}
          </CardBody>
        </Card>
      </div>
      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="Create and activate"
        description={
          active
            ? `A new version is created and activated immediately, retiring v${active.version}.`
            : 'A new version is created and activated immediately.'
        }
        confirmLabel="Create and activate"
        onConfirm={() => submit(true)}
        disabled={blocked}
      >
        {active && (
          <ConfigDiff
            before={active.content}
            after={content}
            beforeLabel={`v${active.version}`}
            afterLabel="new"
          />
        )}
      </ConfirmDialog>
    </>
  );
}

function MissingSection({ name, onFix }: { name: string; onFix: () => void }) {
  return (
    <InlineAlert tone="danger">
      Section <code>{name}</code> is missing or not an object.{' '}
      <button type="button" className="underline" onClick={onFix}>
        Insert default section
      </button>
    </InlineAlert>
  );
}
