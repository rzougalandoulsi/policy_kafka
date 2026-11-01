import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { ChevronDown, LogOut } from 'lucide-react';
import { useAuth } from '@/auth/AuthProvider';
import { Badge } from '@/components/ui/badge';
import { formatInstant } from '@/lib/time';

export function UserMenu() {
  const { session, entitlements, logout } = useAuth();
  if (!session) return null;
  const role = entitlements.isAdmin ? 'admin' : entitlements.isAuditor ? 'auditor' : 'no governance role';
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger
        className="flex items-center gap-2 rounded-md px-2 py-1 text-sm hover:bg-surface-2"
        aria-label="Account menu"
      >
        <span className="font-medium">{session.principalId}</span>
        <Badge tone={entitlements.isAdmin ? 'ok' : entitlements.isAuditor ? 'info' : 'warn'}>{role}</Badge>
        <ChevronDown className="h-4 w-4 text-muted" aria-hidden />
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="end"
          sideOffset={6}
          className="z-50 w-72 rounded-md border border-border bg-surface p-2 text-sm shadow-lg"
        >
          <div className="px-2 py-1.5">
            <p className="text-xs text-muted">
              Signed in via {session.source === 'oidc' ? 'OIDC' : 'pasted token'}
            </p>
            <p className="mt-1 text-xs">Roles: {session.roles.length ? session.roles.join(', ') : '—'}</p>
            {session.teams.length > 0 && <p className="text-xs">Teams: {session.teams.join(', ')}</p>}
            <p className="text-xs text-muted">Token expires {formatInstant(session.expiresAt)}</p>
          </div>
          <DropdownMenu.Separator className="my-1 h-px bg-border" />
          <DropdownMenu.Item
            className="flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 outline-none hover:bg-surface-2 focus:bg-surface-2"
            onSelect={() => void logout()}
          >
            <LogOut className="h-4 w-4" aria-hidden /> Sign out
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
