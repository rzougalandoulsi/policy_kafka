import { Link } from 'react-router-dom';
import { useAuth } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/button';
import { Card, CardBody } from '@/components/ui/card';

export function ForbiddenPage() {
  const { session, config, logout } = useAuth();
  return (
    <div className="mx-auto mt-16 max-w-lg px-4">
      <Card>
        <CardBody className="flex flex-col gap-3 py-8">
          <h1 className="text-lg font-semibold">No governance role</h1>
          <p className="text-sm text-muted">
            <span className="font-medium text-text">{session?.principalId}</span> is signed in but carries
            none of the roles this console needs: <code>{config.auditorRole}</code> (read) or{' '}
            <code>{config.adminRole}</code> (read and write).
          </p>
          <p className="text-sm text-muted">
            Roles present: {session?.roles.length ? session.roles.join(', ') : 'none'}.
          </p>
          <Button variant="secondary" onClick={() => void logout()}>
            Sign in as someone else
          </Button>
        </CardBody>
      </Card>
    </div>
  );
}

export function NotFoundPage() {
  return (
    <div className="py-16 text-center">
      <h1 className="text-lg font-semibold">Page not found</h1>
      <Link to="/" className="mt-2 inline-block text-sm text-primary hover:underline">
        Back to overview
      </Link>
    </div>
  );
}
