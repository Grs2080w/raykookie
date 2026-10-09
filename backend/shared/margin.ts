// Alerta de margem: compara o preço/margem de uma receita antes e depois de um ingrediente mudar de preço.
import type { PricingResult } from './pricing';

export type MarginAlert = { kind: 'margem' | 'preco'; before: number; after: number; suggested: number | null };

export const MARGIN_DROP_POINTS = 5; // queda de margem (pontos percentuais) que já merece aviso, mesmo acima do mínimo
export const PRICE_RISE_PCT = 5; // aumento do preço sugerido que merece aviso quando não há preço fixado

/**
 * Com preço do cardápio fixado (`withSale`): avisa quando a margem real cai abaixo do mínimo ou perde 5 pontos ou mais.
 * Sem preço fixado: o preço sugerido sobe sozinho, então avisa quando ele sobe 5% ou mais ("o cardápio vai mudar").
 */
export function marginAlert(before: PricingResult, after: PricingResult, opts: { withSale: boolean; minMarginPct: number; suggestedAfter: number }): MarginAlert | null {
  if (opts.withSale) {
    const b = before.realMarginPct, a = after.realMarginPct;
    if (a < b - 0.05 && (a < opts.minMarginPct || b - a >= MARGIN_DROP_POINTS)) return { kind: 'margem', before: round2(b), after: round2(a), suggested: opts.suggestedAfter };
    return null;
  }
  if (before.rounded > 0 && after.rounded > before.rounded && (after.rounded / before.rounded - 1) * 100 >= PRICE_RISE_PCT) {
    return { kind: 'preco', before: before.rounded, after: after.rounded, suggested: after.rounded };
  }
  return null;
}
const round2 = (n: number) => Math.round(n * 100) / 100;
