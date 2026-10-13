import { ExternalLink } from 'lucide-react';
import { PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import type { CardapioRecipe, CombosPage } from '@shared/types';
import { CardapioManager } from './CardapioManager';

export const metadata = { title: 'Cardápio' };

// A rota é /cardapio-do-site porque /cardapio é a página pública do site (no domínio da gestão ela redireciona para o site).
export default async function Page() {
  const [recipes, combos] = await Promise.all([apiGet<CardapioRecipe[]>('/api/recipes/cardapio'), apiGet<CombosPage>('/api/combos')]);
  return (
    <>
      <PageHeader title="Cardápio" subtitle="O que aparece na landing page, no app do cliente e no link de compartilhar. O preço é o da receita padrão de cada produto."
        action={<a href="/cardapio" target="_blank" rel="noreferrer" className="inline-flex items-center gap-2 rounded-xl px-4 py-2.5 text-[15px] font-semibold text-pink-strong hover:bg-pink-tint"><ExternalLink size={17} />Ver cardápio</a>} />
      <CardapioManager recipes={recipes} combos={combos} />
    </>
  );
}
