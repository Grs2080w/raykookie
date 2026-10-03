/** @type {import('next').NextConfig} */
export default {
  poweredByHeader: false,
  distDir: process.env.NEXT_DIST_DIR || '.next', // só para testar um build sem mexer no que está no ar
  outputFileTracingRoot: new URL('..', import.meta.url).pathname, // raiz do repositório (inclui ../shared)
  serverExternalPackages: ['pg'],
  experimental: { externalDir: true }, // permite importar ../shared
};
