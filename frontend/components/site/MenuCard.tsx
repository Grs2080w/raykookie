import Link from 'next/link';
import { TrackedItem } from '@/components/site/TrackedItem';
import { money } from '@/lib/format';
import { niceName, placeholderTone } from '@/lib/menu-view';
import { weightLabel } from '@shared/shop';
import { menuTagLabel } from '@shared/menu-tags';
import type { MenuItem } from '@shared/types';

/** Foto do item (sempre 4:3, como vem do painel) ou, sem foto, o placeholder da marca: o símbolo do cookie sobre um tom do doce. */
function Media({ item }: { item: MenuItem }) {
  if (item.image) {
    // eslint-disable-next-line @next/next/no-img-element
    return <img src={item.image} alt="" width={400} height={300} loading="lazy" decoding="async" />;
  }
  return (
    <span className="rk-ph" data-tone={placeholderTone(item)} aria-hidden>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/brand/raykookie-simbolo-cookie.svg" alt="" width={96} height={96} loading="lazy" decoding="async" />
    </span>
  );
}

/**
 * Cartão de um item do cardápio. "Pedir" leva ao novo pedido com o item na URL (`item`) e a origem (`from`): sem sessão,
 * o login devolve a pessoa para exatamente essa página. O link cobre o cartão inteiro (área de toque grande); o botão é o rótulo.
 */
export function MenuCard({ item, from }: { item: MenuItem; from: 'cardapio' | 'home' }) {
  const name = niceName(item.name);
  const weight = weightLabel(item.weight_g);
  // Características: "Vegetariano", "Sem lactose"… viram um chip cada; os "Contém …" se juntam em um só ("Contém soja e ovos")
  const diet = (item.tags ?? []).filter((t) => !t.startsWith('contem_')).map(menuTagLabel);
  const contains = (item.tags ?? []).filter((t) => t.startsWith('contem_')).map((t) => menuTagLabel(t).replace(/^Contém /, ''));
  const containsLabel = contains.length ? `Contém ${contains.length > 1 ? `${contains.slice(0, -1).join(', ')} e ${contains.at(-1)}` : contains[0]}` : '';
  const savings = item.combo && item.combo.savings > 0 ? item.combo : null;
  return (
    <TrackedItem itemId={item.id} surface={from === 'home' ? 'landing' : 'cardapio'} className="rk-card">
      <div className="rk-card__media"><Media item={item} /></div>
      <div className="rk-card__body">
        <h4 className="rk-card__name">{name}</h4>
        {weight && <p className="rk-card__meta">{weight} cada</p>}
        {item.combo && (
          <ul className="rk-card__parts" aria-label="O que vem no combo">
            {item.combo.parts.map((p) => <li key={p.id}>{p.qty} {p.name}</li>)}
          </ul>
        )}
        {!!item.ingredients?.length && <p className="rk-card__meta">Ingredientes: {item.ingredients.join(', ')}</p>}
        {savings && <p className="rk-card__save">Economiza {money(savings.savings)}</p>}
        {!!(diet.length || containsLabel) && (
          <ul className="rk-tags" aria-label="Características">
            {diet.map((t) => <li key={t}>{t}</li>)}
            {containsLabel && <li>{containsLabel}</li>}
          </ul>
        )}
        <div className="rk-card__foot">
          <p className="rk-card__price">
            {savings && <s>avulso {money(savings.full_price)}</s>}
            <strong className="tnum">{money(item.price)}</strong>
          </p>
          <Link href={`/cliente/pedidos/novo?item=${encodeURIComponent(item.id)}&from=${from}`} data-track-click className="rk-card__cta" aria-label={`Pedir ${name}`}>Pedir</Link>
        </div>
      </div>
    </TrackedItem>
  );
}
