import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath, URL } from 'node:url';

// Dev server: same-origin proxies so the browser never deals with CORS.
//   /api  -> Governance (localhost:8080)
//   /oidc -> mock OIDC (localhost:18089) with Host rewritten to what Governance validates as issuer.
const GOVERNANCE = process.env.GOVERNANCE_URL ?? 'http://localhost:8080';
const OIDC = process.env.OIDC_URL ?? 'http://localhost:18089';
const OIDC_HOST = process.env.OIDC_HOST ?? 'mock-oidc:8080';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 3000,
    proxy: {
      '/api': { target: GOVERNANCE, changeOrigin: true },
      '/oidc': {
        target: OIDC,
        changeOrigin: false,
        rewrite: (p) => p.replace(/^\/oidc/, ''),
        headers: { host: OIDC_HOST },
      },
    },
  },
  build: {
    sourcemap: false,
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      output: {
        // Long-lived vendor chunks so a UI change does not invalidate React/Query/OIDC for returning users.
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          data: ['@tanstack/react-query', 'oidc-client-ts', 'zod', 'react-hook-form', '@hookform/resolvers'],
          ui: [
            '@radix-ui/react-dialog',
            '@radix-ui/react-tabs',
            '@radix-ui/react-switch',
            '@radix-ui/react-dropdown-menu',
            'lucide-react',
            'sonner',
            'date-fns',
            'fflate',
          ],
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
