import type { MetadataRoute } from 'next';
import { headers } from 'next/headers';
import { GESTAO_HOST, SITE_URL } from '@/lib/urls';

/** robots.txt: o painel (GESTAO_HOST) não é indexado; no site, só ficam de fora as áreas privadas. */
export default async function robots(): Promise<MetadataRoute.Robots> {
  const h = await headers();
  const host = (h.get('x-forwarded-host') ?? h.get('host') ?? '').toLowerCase();
  if (host === GESTAO_HOST) return { rules: { userAgent: '*', disallow: '/' } };
  return {
    rules: { userAgent: '*', allow: '/', disallow: ['/api/', '/cliente/', '/pagar/'] },
    sitemap: `${SITE_URL}/sitemap.xml`,
  };
}
