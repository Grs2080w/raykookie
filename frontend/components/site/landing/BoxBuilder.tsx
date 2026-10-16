'use client';
import { useState } from 'react';
import { TrackedItem } from '@/components/site/TrackedItem';
import { ShareMenu } from '@/components/ShareMenu';
import { money } from '@/lib/format';
import { track } from '@/lib/track';
import { CARD_INSTALLMENTS_MIN_AMOUNT, MAX_INSTALLMENTS, MAX_QTY, weightLabel } from '@shared/shop';
import { menuTagLabel } from '@shared/menu-tags';
import type { Menu, MenuItem } from '@shared/types';

type Box = Record<string, number>;

/** Link do novo pedido já com a caixa inteira (`itens=id:qtd,...`); sem sessão, o login devolve a pessoa para ele. */
const orderHref = (box: Box) => {
  const itens = Object.entries(box).filter(([, n]) => n > 0).map(([id, n]) => `${id}:${n}`).join(',');
  return `/cliente/pedidos/novo?itens=${encodeURIComponent(itens)}&from=home`;
};

/** "R$ 34,50" em três partes, para o número grande: símbolo, reais e centavos. */
function splitMoney(v: number) {
  const [int, cents] = v.toFixed(2).split('.');
  return { int: Number(int).toLocaleString('pt-BR'), cents };
}

function describe(m: MenuItem) {
  const parts: string[] = [];
  if (m.combo) parts.push(m.combo.contents);
  if (m.ingredients?.length) parts.push(`Ingredientes: ${m.ingredients.join(', ')}`);
  return parts.join('. ');
}

/**
 * Cardápio (das receitas do painel) e "Sua caixa": o + de cada item põe na caixa e o total aparece bem grande, com a
 * data real de entrega. O + é um link de verdade para pedir aquele item, então sem script a página continua funcionando.
 */
export function BoxBuilder({ menu, delivery }: { menu: Menu; delivery: string }) {
  const [box, setBox] = useState<Box>({});
  const [bump, setBump] = useState(0);
  const byId = new Map(menu.items.map((m) => [m.id, m]));
  const lines = Object.entries(box).filter(([id, n]) => n > 0 && byId.has(id));
  const count = lines.reduce((s, [, n]) => s + n, 0);
  const total = lines.reduce((s, [id, n]) => s + byId.get(id)!.price * n, 0);
  const { int, cents } = splitMoney(total);
  const combos = menu.groups.find((g) => g.items.some((i) => i.combo))?.items ?? [];

  // ── 1. mexer na caixa
  const change = (id: string, delta: number) => {
    setBox((b) => ({ ...b, [id]: Math.min(MAX_QTY, Math.max(0, (b[id] ?? 0) + delta)) }));
    setBump((n) => n + 1);
    if (delta > 0) track('cart_add', { item_id: id, qty: 1 });
  };

  if (menu.groups.length === 0) {
    return (
      <section id="cardapio" className="menu wrap">
        <h2 className="display">O que sai do forno</h2>
        <p className="mt-6 max-w-[40ch]">Nosso cardápio está sendo preparado. Volte em breve!</p>
      </section>
    );
  }

  return (
    <>
      {/* ── 2. o cardápio */}
      <section id="cardapio" className="menu wrap" aria-labelledby="cardapio-titulo">
        <div className="menu-head">
          <h2 id="cardapio-titulo" className="display" data-reveal>O que sai do forno</h2>
          <div data-reveal style={{ '--i': 1 } as React.CSSProperties}>
            <p>Toque no + para pôr na sua caixa. Precisa de outro sabor, tamanho ou decoração? Conte nas observações do pedido.</p>
            <div className="mt-4"><ShareMenu /></div>
          </div>
        </div>
        {menu.groups.length > 1 && (
          <nav className="cat-jump" aria-label="Categorias do cardápio">
            {menu.groups.map((g) => <a key={g.id} href={`#cat-${g.id}`}>{g.name}</a>)}
          </nav>
        )}
        {menu.groups.map((g) => (
          <div key={g.id} id={`cat-${g.id}`} className="cat">
            <div className="cat-title" data-reveal>
              <h3 className="display">{g.name}</h3>
              <p className="small soft">{g.items.length} {g.items.length === 1 ? 'item' : 'itens'}</p>
            </div>
            <ul className="cat-list">
              {g.items.map((m, i) => {
                const n = box[m.id] ?? 0;
                const note = [m.combo && m.combo.savings > 0 ? `Economize ${money(m.combo.savings)}` : '', ...(m.tags ?? []).map(menuTagLabel)].filter(Boolean).join(', ');
                return (
                  <TrackedItem key={m.id} itemId={m.id} surface="landing" className="item">
                    <div className="item-mark" data-reveal="scale" style={{ '--i': i % 2 } as React.CSSProperties}>
                      {m.image
                        // eslint-disable-next-line @next/next/no-img-element
                        ? <div className="disc-photo bite"><img src={m.image} alt={m.name} width={128} height={128} loading="lazy" /></div>
                        : <div className="disc bite" aria-hidden />}
                    </div>
                    <div data-reveal style={{ '--i': (i % 2) + 1 } as React.CSSProperties}>
                      <p className="item-name"><span>{m.name}{weightLabel(m.weight_g) && <span className="item-weight">{weightLabel(m.weight_g)}</span>}</span><span>{money(m.price)}</span></p>
                      {(describe(m) || note) && <p className="item-desc">{describe(m)}{describe(m) && note ? '. ' : ''}{note && <span className="item-note">{note}</span>}</p>}
                    </div>
                    <a
                      href={`/cliente/pedidos/novo?item=${encodeURIComponent(m.id)}&from=home`}
                      data-track-click
                      className="add"
                      data-on={n > 0}
                      aria-label={n > 0 ? `${m.name}: ${n} na caixa. Pôr mais um` : `Pôr ${m.name} na caixa`}
                      onClick={(e) => { e.preventDefault(); change(m.id, 1); }}
                    >
                      <span key={n} className={n > 0 ? 'count pop' : undefined}>{n > 0 ? n : '+'}</span>
                    </a>
                  </TrackedItem>
                );
              })}
            </ul>
          </div>
        ))}
      </section>

      {/* ── 3. a caixa: o total, bem grande */}
      <section id="caixa" className="box wrap" aria-labelledby="caixa-titulo">
        <div className="box-intro">
          <h2 id="caixa-titulo" className="display" data-reveal>Sua caixa</h2>
          <p data-reveal style={{ '--i': 1 } as React.CSSProperties}>
            {count === 0 ? 'Ainda vazia. Toque no + nos itens do cardápio, ou comece por um combo:' : 'Ajuste as quantidades aqui. No pedido você escolhe o dia, a entrega e o pagamento.'}
          </p>
          {count === 0 ? (
            <div className="quick" data-reveal style={{ '--i': 2 } as React.CSSProperties}>
              {combos.map((c) => <button key={c.id} type="button" onClick={() => change(c.id, 1)}>{c.name.toLowerCase()} · {money(c.price)}</button>)}
            </div>
          ) : (
            <ul className="box-lines" aria-live="polite">
              {lines.map(([id, n]) => (
                <li key={id} className="box-line">
                  <span>{n}× {byId.get(id)!.name}</span>
                  <button type="button" onClick={() => change(id, -1)} aria-label={`Tirar um ${byId.get(id)!.name}`}>−</button>
                  <button type="button" onClick={() => change(id, 1)} aria-label={`Pôr mais um ${byId.get(id)!.name}`}>+</button>
                </li>
              ))}
            </ul>
          )}
        </div>
        <div>
          <div className="fig-wrap" data-reveal style={{ '--i': 1 } as React.CSSProperties}>
            <p className="fig display" aria-live="polite" aria-label={`Total da caixa: ${money(total)}`}>
              <span className="cur">R$</span><span key={bump} className={`fig-num${bump ? ' tick' : ''}`}>{int}</span><span className="cents">,{cents}</span>
            </p>
            <div className="fig-disc" aria-hidden>
              <div className="disc bite" style={{ position: 'absolute', inset: 0 }} />
              <span key={count} className={count ? 'pop' : undefined}>{count === 0 ? 'caixa vazia' : `${count} ${count === 1 ? 'item' : 'itens'}`}</span>
            </div>
          </div>
          <div className="includes" data-reveal style={{ '--i': 2 } as React.CSSProperties}>
            <p>Pronto a partir de {delivery}</p>
            <p>Retirada sem taxa ou entrega em casa</p>
            <p>Pix, cartão de crédito ou dinheiro</p>
            <p>Até {MAX_INSTALLMENTS}x sem juros acima de {money(CARD_INSTALLMENTS_MIN_AMOUNT)}</p>
          </div>
          <a href={count ? orderHref(box) : '#cardapio'} className="btn" aria-disabled={count === 0} data-reveal style={{ '--i': 3 } as React.CSSProperties}>
            {count ? `Pedir esta caixa` : 'Escolha no cardápio'}
          </a>
        </div>
      </section>
    </>
  );
}
