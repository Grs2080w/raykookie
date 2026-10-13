// Preço de combo, igual ao do servidor (ComboPricing) e ao do app (mobile-common/core/combos.dart): sugerido = soma
// avulsa menos o desconto de combo, nunca abaixo do custo + margem mínima, arredondado para cima em R$ 0,50; o preço
// escolhido vale no lugar dele.
export type ComboPart = { qty: number; price: number; unitCost: number };
export type ComboCalc = { fullPrice: number; cost: number; suggested: number; price: number; profit: number; marginPct: number; belowCost: boolean; savings: number };

const cents = (v: number) => Math.round(v * 100) / 100;

/** Arredonda para cima em R$ 0,50 (sem o ruído de ponto flutuante). */
export const roundUpHalf = (v: number) => Math.ceil(Math.round(v * 100) / 50) / 2;

export function comboCalc(parts: ComboPart[], opts: { discountPct: number; minMarginPct: number; fixedPrice?: number | null }): ComboCalc {
  let full = 0, cost = 0;
  for (const p of parts) { full += p.qty * p.price; cost += p.qty * p.unitCost; }
  const discounted = full * (1 - opts.discountPct / 100);
  const floor = cost * (1 + opts.minMarginPct / 100);
  const suggested = roundUpHalf(Math.max(discounted, floor));
  const price = opts.fixedPrice && opts.fixedPrice > 0 ? opts.fixedPrice : suggested;
  const profit = price - cost;
  const margin = cost > 0 ? (profit / cost) * 100 : 0;
  return {
    fullPrice: cents(full), cost: cents(cost), suggested, price: cents(price), profit: cents(profit), marginPct: Math.round(margin * 10) / 10,
    belowCost: profit < 0, savings: full - price > 0 ? cents(full - price) : 0,
  };
}
