import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import type { User } from 'oidc-client-ts';
import { config, type UiConfig } from '@/config';
import { setTokenProvider, setUnauthorizedHandler } from '@/api/client';
import { getUserManager } from './oidc';
import { entitlements, sessionFromToken, type Entitlements, type Session } from './session';
import { isExpired } from './claims';

const DEV_TOKEN_KEY = 'governance-ui.dev-token';

export interface AuthState {
  status: 'loading' | 'anonymous' | 'authenticated';
  session: Session | null;
  entitlements: Entitlements;
  config: UiConfig;
  login: (returnTo?: string) => Promise<void>;
  loginWithToken: (token: string) => boolean;
  logout: () => Promise<void>;
  completeLogin: () => Promise<string>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children, cfg = config }: { children: ReactNode; cfg?: UiConfig }) {
  const [session, setSession] = useState<Session | null>(null);
  const [status, setStatus] = useState<AuthState['status']>('loading');
  const manager = useMemo(() => getUserManager(cfg), [cfg]);
  // The API layer reads the token through this ref, which is updated synchronously in `adopt`. Reading it
  // from state via an effect would race page queries: child effects (React Query fetches) run before the
  // provider's own effect, so the first request after a reload would go out without a bearer token.
  const sessionRef = useRef<Session | null>(null);

  const adopt = useCallback((s: Session | null) => {
    sessionRef.current = s;
    setSession(s);
    setStatus(s ? 'authenticated' : 'anonymous');
  }, []);

  const fromUser = useCallback(
    (u: User | null) => (u && !u.expired ? sessionFromToken(u.access_token, 'oidc', cfg) : null),
    [cfg],
  );

  // Boot: dev token first (explicit operator choice), then any OIDC user already in session storage.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (cfg.devTokenLogin) {
        const stored = sessionStorage.getItem(DEV_TOKEN_KEY);
        if (stored) {
          const s = sessionFromToken(stored, 'token', cfg);
          if (s && !isExpired(s.claims)) {
            if (!cancelled) adopt(s);
            return;
          }
          sessionStorage.removeItem(DEV_TOKEN_KEY);
        }
      }
      try {
        const u = await manager.getUser();
        if (!cancelled) adopt(fromUser(u));
      } catch {
        if (!cancelled) adopt(null);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [cfg, manager, adopt, fromUser]);

  // Keep the session in step with silent renewals / expiry.
  useEffect(() => {
    const onLoaded = (u: User) => adopt(fromUser(u));
    const onExpired = () => {
      if (sessionRef.current?.source === 'oidc') adopt(null);
    };
    manager.events.addUserLoaded(onLoaded);
    manager.events.addAccessTokenExpired(onExpired);
    return () => {
      manager.events.removeUserLoaded(onLoaded);
      manager.events.removeAccessTokenExpired(onExpired);
    };
  }, [manager, adopt, fromUser]);

  useEffect(() => {
    setTokenProvider(() => sessionRef.current?.accessToken ?? null);
    setUnauthorizedHandler(() => {
      // Token rejected by the API: drop it so the user is sent back to login.
      sessionStorage.removeItem(DEV_TOKEN_KEY);
      adopt(null);
    });
    return () => setUnauthorizedHandler(null);
  }, [adopt]);

  const value = useMemo<AuthState>(
    () => ({
      status,
      session,
      entitlements: entitlements(session, cfg),
      config: cfg,
      login: async (returnTo) => {
        await manager.signinRedirect({ state: { returnTo: returnTo ?? window.location.pathname } });
      },
      loginWithToken: (token) => {
        const s = sessionFromToken(token.trim(), 'token', cfg);
        if (!s || isExpired(s.claims)) return false;
        sessionStorage.setItem(DEV_TOKEN_KEY, token.trim());
        adopt(s);
        return true;
      },
      logout: async () => {
        sessionStorage.removeItem(DEV_TOKEN_KEY);
        const wasOidc = session?.source === 'oidc';
        adopt(null);
        if (wasOidc) {
          try {
            await manager.removeUser();
          } catch {
            /* ignore */
          }
        }
      },
      completeLogin: async () => {
        const u = await manager.signinCallback();
        adopt(fromUser(u ?? null));
        const st = (u?.state ?? {}) as { returnTo?: string };
        return st.returnTo && st.returnTo !== '/callback' ? st.returnTo : '/';
      },
    }),
    [status, session, cfg, manager, adopt, fromUser],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
