import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthProvider';
import { Spinner } from '@/components/ui/spinner';
import { InlineAlert } from '@/components/ui/states';
import { Button } from '@/components/ui/button';

export function CallbackPage() {
  const { completeLogin } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const ran = useRef(false);
  useEffect(() => {
    if (ran.current) return; // React StrictMode double-invokes effects; the code can be redeemed once.
    ran.current = true;
    completeLogin()
      .then((to) => navigate(to, { replace: true }))
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, [completeLogin, navigate]);
  if (error)
    return (
      <div className="mx-auto mt-20 flex max-w-md flex-col gap-3 px-4">
        <InlineAlert tone="danger">Sign-in failed: {error}</InlineAlert>
        <Button variant="secondary" onClick={() => navigate('/login', { replace: true })}>
          Back to sign in
        </Button>
      </div>
    );
  return <Spinner label="Completing sign-in" full />;
}
