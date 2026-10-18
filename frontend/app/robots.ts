import type { MetadataRoute } from 'next';
import { headers } from 'next/headers';

const site = process.env.SITE_URL || 'https://raykookie.com.br';
const GESTAO_HOST = process.env.GESTAO_HOST || 'business.raykookie.com.br';

/** robots.txt: o painel (GESTAO_HOST) não é indexado; no site, só ficam de fora as áreas privadas. */
export default async function robots(): Promise<MetadataRoute.Robots> {
  const h = await headers();
  const host = (h.get('x-forwarded-host') ?? h.get('host') ?? '').toLowerCase();
  if (host === GESTAO_HOST) return { rules: { userAgent: '*', disallow: '/' } };
  return {
    rules: { userAgent: '*', allow: '/', disallow: ['/api/', '/cliente/', '/pagar/'] },
    sitemap: `${site}/sitemap.xml`,
  };
}
