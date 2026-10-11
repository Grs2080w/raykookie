import Link from 'next/link';
import { Photo } from '@/components/site/Photo';
import { money } from '@/lib/format';
import { photoKind, weightLabel } from '@shared/shop';
import { menuTagLabel } from '@shared/menu-tags';
import type { Menu } from '@shared/types';

/**
 * Cardápio (vem da página Cardápio do painel: foto, nome, preço, selos, ingredientes e combos). "Pedir" leva ao novo pedido com o item na URL
 * (`item`) e a origem (`from`): sem sessão, o login devolve a pessoa para exatamente essa página.
 */
export function MenuList({ menu, from }: { menu: Menu; from: 'cardapio' | 'home' }) {
  if (menu.groups.length === 0) {
    return <p className="mt-6 rounded-3xl border border-dashed border-line px-6 py-12 text-center text-muted">Nosso cardápio está sendo preparado. Volte em breve!</p>;
  }
  return (
    <>
      {menu.groups.length > 1 && (
        <nav className="-mx-4 mt-5 flex gap-2 overflow-x-auto px-4 pb-1 [&::-webkit-scrollbar]:hidden md:mx-0 md:px-0" aria-label="Categorias do cardápio">
          {menu.groups.map((g) => <a key={g.id} href={`#cat-${g.id}`} className="shrink-0 rounded-full border border-line bg-white px-4 py-2 text-sm font-bold hover:border-pink">{g.name}</a>)}
        </nav>
      )}
      {menu.groups.map((g) => (
        <div key={g.id} id={`cat-${g.id}`} className={`scroll-mt-24 ${menu.groups.length > 1 ? 'pt-10' : 'pt-6'}`}>
          {menu.groups.length > 1 && <h3 className="font-display text-2xl font-semibold">{g.name}</h3>}
          <ul className="mt-4 grid gap-3 md:grid-cols-2">
            {g.items.map((m) => (
              <li key={m.id} className="flex items-center gap-3.5 rounded-3xl border border-line bg-white p-3 shadow-card">
                <Photo kind={photoKind(m)} src={m.image} label={m.name} className="size-24 shrink-0 sm:size-28" rounded="rounded-2xl" />
                <div className="flex min-w-0 flex-1 flex-col gap-2">
                  <p className="text-lg font-bold leading-snug">{m.name}</p>
                  {weightLabel(m.weight_g) && <p className="-mt-1 text-sm text-muted">{weightLabel(m.weight_g)} cada</p>}
                  {!!m.tags?.length && (
                    <ul className="-mt-1 flex flex-wrap gap-1" aria-label="Características">
                      {m.tags.map((t) => (
                        <li key={t} className="rounded-full bg-pink-tint px-2.5 py-0.5 text-xs font-semibold text-pink-strong">{menuTagLabel(t)}</li>
                      ))}
                    </ul>
                  )}
                  {m.combo && (
                    <div className="-mt-1 text-sm">
                      <p className="font-semibold">{m.combo.contents}</p>
                      {m.combo.savings > 0 && <p className="font-bold text-pink-strong">Economize {money(m.combo.savings)} (avulso sai {money(m.combo.full_price)})</p>}
                    </div>
                  )}
                  {!!m.ingredients?.length && <p className="-mt-1 text-sm text-muted">Ingredientes: {m.ingredients.join(', ')}</p>}
                  <div className="flex items-center justify-between gap-2">
                    <p className="font-display text-2xl font-semibold tnum">{money(m.price)}</p>
                    <Link href={`/cliente/pedidos/novo?item=${encodeURIComponent(m.id)}&from=${from}`} className="inline-flex min-h-11 items-center rounded-xl bg-pink-tint px-4 text-sm font-bold text-pink-strong hover:brightness-95" aria-label={`Pedir ${m.name}`}>Pedir</Link>
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </div>
      ))}
      <p className="mt-8 text-sm text-muted">Precisa de outro sabor, tamanho ou decoração? Conte no campo de observações do pedido.</p>
    </>
  );
}
