import { CategoryNav } from '@/components/site/CategoryNav';
import { MenuCard } from '@/components/site/MenuCard';
import { presentGroups } from '@/lib/menu-view';
import type { Menu } from '@shared/types';

/**
 * Cardápio (vem da página Cardápio do painel: foto, nome, preço, selos, ingredientes e combos). Os grupos passam por
 * `presentGroups` (só na apresentação) e cada item é um `MenuCard`; "Pedir" leva ao novo pedido com o item na URL.
 */
export function MenuList({ menu, from }: { menu: Menu; from: 'cardapio' | 'home' }) {
  const groups = presentGroups(menu);
  if (groups.length === 0) {
    return <p className="rk-empty">Nosso cardápio está sendo preparado. Volte em breve!</p>;
  }
  return (
    <div className="rk-menu">
      {groups.length > 1 && <CategoryNav groups={groups.map(({ id, name }) => ({ id, name }))} />}
      {groups.map((g) => (
        <section key={g.id} id={`cat-${g.id}`} className="rk-group" aria-labelledby={`cat-${g.id}-t`}>
          <h3 id={`cat-${g.id}-t`}>{g.name}</h3>
          <ul className="rk-grid" data-kind={g.items.some((i) => i.combo) ? 'combos' : 'itens'}>
            {g.items.map((m) => <MenuCard key={m.id} item={m} from={from} />)}
          </ul>
        </section>
      ))}
      <p className="rk-menu__note">Precisa de outro sabor, tamanho ou decoração? Conte no campo de observações do pedido.</p>
    </div>
  );
}
