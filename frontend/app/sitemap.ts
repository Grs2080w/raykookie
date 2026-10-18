import type { MetadataRoute } from 'next';

// Mesmo endereço público do layout: o sitemap exige URLs absolutas.
const site = process.env.SITE_URL || 'https://raykookie.com.br';

/** Páginas públicas que o Google pode indexar (o painel, a área do cliente e o link de pagamento ficam de fora). */
export default function sitemap(): MetadataRoute.Sitemap {
  const lastModified = new Date();
  return [
    { url: `${site}/`, lastModified, changeFrequency: 'weekly', priority: 1 },
    { url: `${site}/cardapio`, lastModified, changeFrequency: 'daily', priority: 0.9 },
    { url: `${site}/termos`, changeFrequency: 'yearly', priority: 0.3 },
    { url: `${site}/privacidade`, changeFrequency: 'yearly', priority: 0.3 },
    { url: `${site}/excluir-conta`, changeFrequency: 'yearly', priority: 0.2 },
  ];
}
