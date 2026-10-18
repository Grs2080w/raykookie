import type { MetadataRoute } from 'next';
import { headers } from 'next/headers';
import { GESTAO_HOST, SITE_URL } from '@/lib/urls';

/**
 * robots.txt (https://developers.google.com/crawling/docs/robots-txt/create-robots-txt): o painel (GESTAO_HOST) não é indexado.
 * No site, o Google segue a regra mais específica (o caminho mais longo vence), então `Allow: /api/fotos/` abre as fotos do
 * cardápio mesmo com `Disallow: /api/`. Sem isso o Googlebot não renderiza as imagens da home.
 * `/cliente` (sem a barra) fica liberado de propósito: a página tem `noindex`, e o Google só lê o `noindex` se puder rastrear.
 */
export default async function robots(): Promise<MetadataRoute.Robots> {
  const h = await headers();
  const host = (h.get('x-forwarded-host') ?? h.get('host') ?? '').toLowerCase();
  if (host === GESTAO_HOST) return { rules: { userAgent: '*', disallow: '/' } };
  return {
    rules: { userAgent: '*', allow: ['/', '/api/fotos/'], disallow: ['/api/', '/cliente/', '/pagar/'] },
    sitemap: `${SITE_URL}/sitemap.xml`,
  };
}
