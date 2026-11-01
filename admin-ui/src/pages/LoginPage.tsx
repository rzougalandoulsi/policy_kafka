import { useState, type FormEvent } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { BookOpenCheck } from 'lucide-react';
import { useAuth } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/button';
import { Card, CardBody } from '@/components/ui/card';
import { Field, Textarea } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/states';

export function LoginPage() {
  const { status, login, loginWithToken, config } = useAuth();
  const location = useLocation();
  const returnTo = (location.state as { returnTo?: string } | null)?.returnTo ?? '/';
  const [token, setToken] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (status === 'authenticated') return <Navigate to={returnTo} replace />;

  const submitToken = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!loginWithToken(token)) setError('That is not a valid, unexpired JWT.');
  };

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <Card className="w-full max-w-md">
        <CardBody className="flex flex-col gap-5 py-8">
          <div className="flex items-center gap-2 text-lg font-semibold">
            <BookOpenCheck className="h-6 w-6 text-primary" aria-hidden /> Governance Admin
          </div>
          <p className="text-sm text-muted">
            Sign in with the platform identity provider. You need the <code>{config.auditorRole}</code> role
            to read and <code>{config.adminRole}</code> to change anything.
          </p>
          <Button
            size="lg"
            loading={busy}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await login(returnTo);
              } catch (e) {
                setError(e instanceof Error ? e.message : String(e));
                setBusy(false);
              }
            }}
          >
            Sign in with OIDC
          </Button>
          {config.devTokenLogin && (
            <form onSubmit={submitToken} className="flex flex-col gap-3 border-t border-border pt-4">
              <InlineAlert tone="warn">
                Development mode: paste a bearer token, e.g. from <code>deploy/scripts/token.sh admin-1</code>
                .
              </InlineAlert>
              <Field id="dev-token" label="Access token">
                <Textarea
                  id="dev-token"
                  value={token}
                  onChange={(e) => setToken(e.target.value)}
                  rows={4}
                  spellCheck={false}
                  className="mono text-xs"
                />
              </Field>
              <Button type="submit" variant="secondary" disabled={!token.trim()}>
                Use token
              </Button>
            </form>
          )}
          {error && <InlineAlert tone="danger">{error}</InlineAlert>}
        </CardBody>
      </Card>
    </div>
  );
}
