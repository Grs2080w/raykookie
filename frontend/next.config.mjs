import fs from 'node:fs';

// /api é repassado à API: em produção (Vercel) para API_BASE, o endereço de config/urls.json (a variável de ambiente
// API_BASE, se existir, vale mais); em desenvolvimento (`npm run dev`, porta própria) para o backend de desenvolvimento.
const urls = JSON.parse(fs.readFileSync(new URL('../config/urls.json', import.meta.url), 'utf8'));
const API_BASE = (process.env.API_BASE || urls.API_BASE).replace(/\/$/, '');
const BACKEND_URL = process.env.BACKEND_URL || 'http://127.0.0.1:10006';

/** @type {import('next').NextConfig} */
export default {
  poweredByHeader: false,
  distDir: process.env.NEXT_DIST_DIR || '.next', // só para testar um build sem mexer no que está no ar
  outputFileTracingRoot: new URL('..', import.meta.url).pathname, // raiz do repositório (inclui ../shared e ../config)
  experimental: { externalDir: true }, // permite importar ../shared
  async rewrites() {
    const destination = process.env.NODE_ENV === 'production' ? API_BASE : BACKEND_URL;
    return [{ source: '/api/:path*', destination: `${destination}/api/:path*` }];
  },
};
