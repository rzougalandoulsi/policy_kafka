import { NavLink, Outlet } from 'react-router-dom';
import { Activity, BookOpenCheck, Eye, FileCode2, Group, ScrollText, ShieldCheck, ShieldOff, Users } from 'lucide-react';
import { cn } from '@/lib/utils';
import { UserMenu } from './UserMenu';

const NAV = [
  { to: '/', label: 'Overview', icon: Activity, end: true },
  { to: '/teams', label: 'Teams', icon: Group },
  { to: '/exceptions', label: 'Exceptions', icon: ShieldOff },
  { to: '/policy-configurations', label: 'Policy configuration', icon: ShieldCheck },
  { to: '/policy-bundles', label: 'Policy bundles', icon: FileCode2 },
  { to: '/approval-rules', label: 'Approval rules', icon: Users },
  { to: '/shadow-findings', label: 'Shadow findings', icon: Eye },
  { to: '/audit', label: 'Audit', icon: ScrollText },
];

export function AppShell() {
  return (
    <div className="flex min-h-screen">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded focus:bg-surface focus:px-3 focus:py-2"
      >
        Skip to content
      </a>
      <aside className="hidden w-60 shrink-0 flex-col border-r border-border bg-surface md:flex">
        <div className="flex items-center gap-2 px-5 py-4 text-sm font-semibold">
          <BookOpenCheck className="h-5 w-5 text-primary" aria-hidden />
          Governance Admin
        </div>
        <nav aria-label="Main" className="flex flex-1 flex-col gap-0.5 px-3">
          {NAV.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-2 rounded-md px-2.5 py-2 text-sm text-muted hover:bg-surface-2 hover:text-text',
                  isActive && 'bg-surface-2 font-medium text-text',
                )
              }
            >
              <Icon className="h-4 w-4" aria-hidden />
              {label}
            </NavLink>
          ))}
        </nav>
        <div className="border-t border-border px-5 py-3 text-xs text-muted">
          <a className="hover:underline" href="/swagger-ui.html" target="_blank" rel="noreferrer">
            OpenAPI
          </a>
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-between gap-3 border-b border-border bg-surface px-4 py-2 md:px-6">
          <nav aria-label="Main (compact)" className="flex gap-1 overflow-x-auto md:hidden">
            {NAV.map(({ to, label, end }) => (
              <NavLink
                key={to}
                to={to}
                end={end}
                className={({ isActive }) =>
                  cn(
                    'whitespace-nowrap rounded px-2 py-1 text-xs',
                    isActive ? 'bg-surface-2 font-medium' : 'text-muted',
                  )
                }
              >
                {label}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto">
            <UserMenu />
          </div>
        </header>
        <main id="main" className="flex-1 px-4 py-5 md:px-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
