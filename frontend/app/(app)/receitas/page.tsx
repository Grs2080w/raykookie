import { ChefHat, Image as ImageIcon, Plus } from 'lucide-react';
import Link from 'next/link';
import { CategoryChip } from '@/components/CategoryIcon';
import { EmptyState, PageHeader } from '@/components/PageHeader';
import { Info } from 'lucide-react';
import { Card } from '@/components/ui/form';
import { ShareMenu } from '@/components/ShareMenu';
import { apiGet } from '@/lib/server-api';
import type { Category, PricedRecipe } from '@shared/types';
import { money, number, percent } from '@/lib/format';

export const metadata = { title: 'Receitas' };

const btn = 'inline-flex items-center gap-2 rounded-xl bg-pink px-4.5 py-2.5 text-[15px] font-semibold text-on-pink shadow-[0_6px_18px_-8px_var(--pink)] transition-all hover:bg-pink-strong active:scale-[0.97]';

export default async function Page() {
  const [recipes, cats] = await Promise.all([apiGet<PricedRecipe[]>('/api/recipes'), apiGet<Category[]>('/api/categories?kind=recipe')]);
  const catMap = new Map(cats.map((c) => [c.id, c]));
  return (
    <>
      <PageHeader title="Receitas e preços" subtitle="Cada receita mostra o custo por unidade e o preço para vender com a margem que você quer."
        action={<div className="flex flex-wrap items-center gap-2">{recipes.length > 0 && <ShareMenu />}{recipes.length > 0 && <a href="/api/story/cardapio" target="_blank" rel="noopener" download="story-cardapio.png" className="inline-flex min-h-11 items-center gap-2 rounded-xl border-2 border-line bg-surface px-4 text-sm font-bold text-ink hover:border-pink"><ImageIcon size={17} />Story do cardápio</a>}<Link href="/receitas/nova" className={btn}><Plus size={18} />Nova receita</Link></div>} />
      <p className="mb-5 flex items-start gap-2.5 rounded-2xl bg-pink-tint px-4 py-3 text-sm text-pink-strong"><Info size={18} className="mt-0.5 shrink-0" />
        <span>Todas as receitas com preço aparecem na Landing page, mas <strong>apenas foto, nome e preço por unidade</strong> vão para lá. Ingredientes, custos e margem ficam só aqui no painel.</span></p>
      {recipes.length === 0 ? (
        <EmptyState icon={<ChefHat size={26} />} title="Nenhuma receita ainda" text="Monte a primeira escolhendo ingredientes do estoque. O preço aparece enquanto você preenche."
          action={<Link href="/receitas/nova" className={btn}><Plus size={18} />Criar receita</Link>} />
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {recipes.map((r) => {
            const c = r.category_id ? catMap.get(r.category_id) : null;
            return (
              <Link key={r.id} href={`/receitas/${r.id}`} className="group block rounded-3xl focus-visible:outline-offset-4">
                <Card className="flex h-full flex-col p-5 transition-all group-hover:-translate-y-0.5 group-hover:border-pink/60">
                  <div className="flex items-start justify-between gap-3">
                    <h2 className="font-display text-xl font-semibold leading-snug">{r.name}</h2>
                    {c && <CategoryChip name={c.name} icon={c.icon} color={c.color} size="sm" />}
                  </div>
                  <p className="mt-1 text-sm text-muted">Rende {r.yield_units} un{r.unit_weight_g > 0 ? ` de ${number(r.unit_weight_g)} g` : ''} · {r.items.length} ingrediente{r.items.length === 1 ? '' : 's'}</p>
                  <p className="mt-2 text-xs font-semibold text-pink-strong">{r.pricing.rounded > 0 ? `Na Landing page${r.photo_v ? ' · com foto' : ''}` : <span className="text-muted">Fora da Landing page (sem preço)</span>}</p>
                  <div className="mt-6 flex items-end justify-between gap-3 border-t border-dashed border-line pt-4">
                    <div>
                      <p className="text-xs text-muted">Preço sugerido</p>
                      <p className="font-display text-3xl font-semibold text-pink-strong tnum">{money(r.pricing.rounded)}</p>
                    </div>
                    <div className="text-right text-sm">
                      <p className="text-muted">Custo <span className="tnum font-semibold text-ink">{money(r.pricing.perUnit)}</span></p>
                      <p className="text-ok tnum font-semibold">Lucro {money(r.pricing.profitPerUnit)} · {percent(r.pricing.realMarginPct)}</p>
                    </div>
                  </div>
                </Card>
              </Link>
            );
          })}
        </div>
      )}
    </>
  );
}
