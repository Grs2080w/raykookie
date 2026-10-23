import fs from 'node:fs';

// /api é repassado à API: em produção (Vercel) para API_BASE, o endereço de config/urls.json (a variável de ambiente
// API_BASE, se existir, vale mais); em desenvolvimento (`npm run dev`, porta própria) para o backend de desenvolvimento.
const urls = JSON.parse(fs.readFileSync(new URL('../config/urls.json', import.meta.url), 'utf8'));
const API_BASE = (process.env.API_BASE || urls.API_BASE).replace(/\/$/, '');
const BACKEND_URL = process.env.BACKEND_URL || 'http://127.0.0.1:10006';

const MP = 'https://*.mercadopago.com https://*.mercadolibre.com https://*.mlstatic.com';

/** Impostas já: não deixam o site ser embutido em outro, trocar a base de links, abrir plugin nem postar formulário fora. */
const CSP_ENFORCED = "frame-ancestors 'none'; base-uri 'self'; object-src 'none'; form-action 'self'";

/**
 * Política completa de recursos, só em modo relatório (o navegador avisa no console o que bloqueraria, sem bloquear):
 * confira o checkout com cartão (Mercado Pago), o CEP (ViaCEP) e as métricas antes de passar para CSP_ENFORCED.
 * 'unsafe-inline' dos scripts existe porque o Next injeta scripts próprios e o tema inicial; o ideal é trocá-lo por nonce.
 */
const CSP_REPORT_ONLY = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline' https://sdk.mercadopago.com https://va.vercel-scripts.com https://www.googletagmanager.com ${MP}`,
  "style-src 'self' 'unsafe-inline'",
  `img-src 'self' data: blob: https://www.google-analytics.com https://www.googletagmanager.com ${MP}`,
  "font-src 'self' data:",
  `connect-src 'self' https://viacep.com.br https://vitals.vercel-insights.com https://va.vercel-scripts.com https://www.google-analytics.com https://*.google-analytics.com https://*.analytics.google.com https://www.googletagmanager.com ${MP}`,
  `frame-src ${MP}`,
  CSP_ENFORCED,
].join('; ');

/** Cabeçalhos de segurança de todas as páginas e respostas do site. */
export const securityHeaders = [
  { key: 'Content-Security-Policy', value: CSP_ENFORCED },
  { key: 'Content-Security-Policy-Report-Only', value: CSP_REPORT_ONLY },
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
  { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=()' },
  { key: 'Strict-Transport-Security', value: 'max-age=31536000; includeSubDomains' },
];

/** @type {import('next').NextConfig} */
export default {
  poweredByHeader: false,
  async headers() {
    return [{ source: '/:path*', headers: securityHeaders }];
  },
  distDir: process.env.NEXT_DIST_DIR || '.next', // só para testar um build sem mexer no que está no ar
  outputFileTracingRoot: new URL('..', import.meta.url).pathname, // raiz do repositório (inclui ../shared e ../config)
  experimental: { externalDir: true }, // permite importar ../shared
  async rewrites() {
    const destination = process.env.NODE_ENV === 'production' ? API_BASE : BACKEND_URL;
    return [{ source: '/api/:path*', destination: `${destination}/api/:path*` }];
  },
};
