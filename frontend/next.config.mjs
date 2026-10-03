// Em produção o backend serve este build na mesma porta (backend/server.mjs), então não há proxy.
// Só no desenvolvimento (`npm run dev`, porta própria) o /api é repassado ao backend de desenvolvimento.
const BACKEND_URL = process.env.BACKEND_URL || 'http://127.0.0.1:10006';

/** @type {import('next').NextConfig} */
export default {
  poweredByHeader: false,
  distDir: process.env.NEXT_DIST_DIR || '.next', // só para testar um build sem mexer no que está no ar
  outputFileTracingRoot: new URL('..', import.meta.url).pathname, // raiz do repositório (inclui ../shared)
  experimental: { externalDir: true }, // permite importar ../shared
  async rewrites() {
    return process.env.NODE_ENV === 'production' ? [] : [{ source: '/api/:path*', destination: `${BACKEND_URL}/api/:path*` }];
  },
};
