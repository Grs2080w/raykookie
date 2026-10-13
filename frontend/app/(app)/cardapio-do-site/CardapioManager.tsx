'use client';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import clsx from 'clsx';
import { Card, Switch } from '@/components/ui/form';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { money } from '@/lib/format';
import { MENU_TAGS } from '@shared/menu-tags';
import type { CardapioRecipe, CombosPage } from '@shared/types';
import { CombosSection } from './CombosSection';

/** Página do Cardápio: por produto, liga/desliga na landing page, mostra os ingredientes e escolhe os selos. Cada mudança salva na hora. */
export function CardapioManager({ recipes, combos }: { recipes: CardapioRecipe[]; combos: CombosPage }) {
  const router = useRouter();
  if (recipes.length === 0) return <p className="rounded-3xl border border-dashed border-line px-6 py-12 text-center text-muted">Cadastre uma receita primeiro, em Receitas.</p>;
  return (
    <div className="flex flex-col gap-10">
      <section>
        <h2 className="mb-1 font-display text-2xl font-semibold">Produtos no cardápio</h2>
        <p className="mb-4 max-w-2xl text-muted">Escolha o que aparece para o cliente. Para cada produto: os selos (alergênicos, dieta…) e se mostra a lista de ingredientes.</p>
        <div className="grid gap-3 lg:grid-cols-2">
          {recipes.map((r) => <RecipeCard key={r.id} recipe={r} onSaved={() => router.refresh()} />)}
        </div>
      </section>
      {/* recarrega junto: ligar/desligar um produto muda o que os combos podem mostrar */}
      <CombosSection initial={combos} />
    </div>
  );
}

function RecipeCard({ recipe, onSaved }: { recipe: CardapioRecipe; onSaved: () => void }) {
  const [show, setShow] = useState(recipe.show_on_menu);
  const [ingredients, setIngredients] = useState(recipe.show_ingredients);
  const [tags, setTags] = useState<string[]>(recipe.tags);
  const [busy, setBusy] = useState(false);

  async function patch(body: Record<string, unknown>) {
    setBusy(true);
    try { await api(`/api/recipes/${recipe.id}/cardapio`, 'PATCH', body); onSaved(); }
    catch (e: any) { toast.error(e.message); setShow(recipe.show_on_menu); setIngredients(recipe.show_ingredients); setTags(recipe.tags); }
    finally { setBusy(false); }
  }
  function toggleTag(id: string) {
    const next = tags.includes(id) ? tags.filter((t) => t !== id) : [...tags, id];
    setTags(next);
    patch({ tags: next });
  }

  return (
    <Card className="flex flex-col gap-3 p-4">
      <div className="flex items-center gap-3.5">
        <span className="grid size-14 shrink-0 place-items-center overflow-hidden rounded-2xl bg-surface-2 text-xl">
          {recipe.photo_v ? <img src={`/api/fotos/${recipe.id}?v=${recipe.photo_v}`} alt="" className="size-full object-cover" /> : '🍪'}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold">{recipe.name}</p>
          {recipe.priced && <p className="text-sm text-muted">{money(recipe.price ?? 0)}</p>}
        </div>
        <Switch checked={show} disabled={busy} label={`${recipe.name} no cardápio`} onChange={(v) => { setShow(v); patch({ show_on_menu: v }); }} />
      </div>
      {!recipe.priced && <p className="text-sm text-warn">Sem preço ainda: não aparece no cardápio mesmo ligado. Adicione os ingredientes em Receitas.</p>}
      {show && (
        <>
          <label className="flex items-center justify-between gap-3 rounded-2xl bg-surface-2 px-3.5 py-2.5">
            <span>
              <span className="block text-sm font-semibold">Mostrar os ingredientes</span>
              <span className="block text-xs text-muted">Da receita padrão, para quem tem alergia.</span>
            </span>
            <Switch checked={ingredients} disabled={busy} label={`Mostrar os ingredientes de ${recipe.name}`} onChange={(v) => { setIngredients(v); patch({ show_ingredients: v }); }} />
          </label>
          <div>
            <p className="mb-1.5 text-xs font-bold text-muted">Características</p>
            <div className="flex flex-wrap gap-1.5">
              {MENU_TAGS.map((t) => (
                <button key={t.id} type="button" disabled={busy} aria-pressed={tags.includes(t.id)} onClick={() => toggleTag(t.id)}
                  className={clsx('rounded-full border px-3 py-1 text-sm transition-colors disabled:opacity-55', tags.includes(t.id) ? 'border-pink bg-pink-tint font-semibold text-pink-strong' : 'border-line text-muted hover:text-ink')}>
                  {t.label}
                </button>
              ))}
            </div>
          </div>
        </>
      )}
    </Card>
  );
}

