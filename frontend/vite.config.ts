import { loadEnv, type Plugin } from 'vite';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * The browser only ever talks to its own origin: every backend call is a relative "/api/..."
 * request. In development Vite forwards those to the backend (VITE_BACKEND_URL, default
 * http://localhost:8080); in production the load balancer does the same. That is why no
 * backend address is baked into the bundle and why the Content-Security-Policy below can
 * say connect-src 'self'.
 */

/**
 * Adds a strict Content-Security-Policy to the PRODUCTION index.html. The access token lives
 * in sessionStorage, which injected script could read, so the policy allows no inline script
 * and no script from anywhere but this origin. (The development server needs inline scripts
 * for hot reload, so the tag is not added there.) style-src allows inline styles because the
 * chart library sets element styles.
 */
function contentSecurityPolicy(): Plugin {
  const policy = [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join('; ');
  return {
    name: 'finbridge-csp',
    apply: 'build',
    transformIndexHtml(html) {
      return html.replace(
        '<meta charset="UTF-8" />',
        `<meta charset="UTF-8" />\n    <meta http-equiv="Content-Security-Policy" content="${policy}" />`,
      );
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'VITE_');
  const backend = env.VITE_BACKEND_URL || 'http://localhost:8080';
  return {
    plugins: [react(), contentSecurityPolicy()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target: backend, changeOrigin: true },
      },
    },
    build: {
      sourcemap: false,
      chunkSizeWarningLimit: 900,
    },
    test: {
      environment: 'node',
      include: ['src/**/*.test.ts'],
    },
  };
});
