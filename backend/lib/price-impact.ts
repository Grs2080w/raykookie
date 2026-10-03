// Quando o preço de um ingrediente muda: guarda no histórico e avisa se alguma receita perdeu margem (ou se o preço do cardápio vai subir).
import { q, q1 } from './db';
import { getFixedTotal, getIngredients, getRecipes, getSettings } from './queries';
import { notifyPriceAlert } from './push';
import { marginAlert } from '@shared/margin';
import { calcRecipe, withSalePrice } from '@shared/pricing';

export type PriceState = { cost: number; size: number };

export async function priceHistory(userId: number, ingredientId: number, limit = 60) {
  return q(
    `SELECT pq.id, pq.price::float8 AS price, pq.pack_size::float8 AS pack_size, pq.pack_unit, pq.source, pq.at, pq.supplier_id, s.name AS supplier
       FROM price_quotes pq LEFT JOIN suppliers s ON s.id = pq.supplier_id
      WHERE pq.user_id = $1 AND pq.ingredient_id = $2 ORDER BY pq.at DESC, pq.id DESC LIMIT $3`, [userId, ingredientId, limit]);
}

/**
 * Chame depois de mudar `package_cost`/`package_size` de um ingrediente. Grava no histórico e, para cada receita que o usa,
 * compara o preço/margem antes e depois (`marginAlert`). Nunca lança erro: é um extra, não pode derrubar a edição.
 */
export async function onPriceChange(userId: number, ingredientId: number, before: PriceState, after: PriceState, source: 'edicao' | 'nota' | 'scraper' | 'manual', supplierId?: number | null): Promise<number> {
  try {
    if (before.cost === after.cost && before.size === after.size) return 0;
    const ing = await q1<{ unit: string; name: string }>('SELECT unit, name FROM ingredients WHERE id = $1 AND user_id = $2', [ingredientId, userId]);
    if (!ing) return 0;
    await q('UPDATE ingredients SET price_updated_at = now() WHERE id = $1', [ingredientId]);
    if (after.size > 0) {
      await q(`INSERT INTO price_quotes(user_id, ingredient_id, supplier_id, price, pack_size, pack_unit, source) VALUES ($1,$2,$3,$4,$5,$6,$7)`,
        [userId, ingredientId, supplierId ?? null, after.cost, after.size, ing.unit, source]);
    }
    const [recipes, ings, settings, fixed] = await Promise.all([getRecipes(userId), getIngredients(userId), getSettings(userId), getFixedTotal(userId)]);
    const uc = (c: number, s: number) => (s > 0 ? c / s : 0);
    const costNow = new Map(ings.map((i) => [i.id, uc(i.package_cost, i.package_size)]));
    const costBefore = new Map(costNow).set(ingredientId, uc(before.cost, before.size));
    const price = (r: (typeof recipes)[number], costs: Map<number, number>) => calcRecipe({
      items: r.items.map((it) => ({ qty: it.qty, unitCost: costs.get(it.ingredient_id) ?? 0 })), yieldUnits: r.yield_units, marginPct: r.margin_pct, prepHours: r.prep_hours,
      hourlyRate: settings.hourly_rate, fixedMonthly: fixed, monthlyUnits: settings.monthly_units,
    });
    let n = 0;
    for (const r of recipes.filter((x) => x.items.some((it) => it.ingredient_id === ingredientId))) {
      const b0 = price(r, costBefore), a0 = price(r, costNow);
      const withSale = !!(r.sale_price && r.sale_price > 0);
      const alert = marginAlert(withSalePrice(b0, r.sale_price), withSalePrice(a0, r.sale_price), { withSale, minMarginPct: settings.min_margin_pct, suggestedAfter: a0.rounded });
      if (!alert) continue;
      const cause = `${ing.name}: R$ ${(uc(before.cost, before.size) * (ing.unit === 'un' ? 1 : 1000)).toFixed(2).replace('.', ',')} → R$ ${(uc(after.cost, after.size) * (ing.unit === 'un' ? 1 : 1000)).toFixed(2).replace('.', ',')}/${ing.unit === 'g' ? 'kg' : ing.unit === 'ml' ? 'L' : 'un'}`;
      await q(`INSERT INTO price_alerts(user_id, recipe_id, ingredient_id, kind, before_value, after_value, suggested, cause) VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`,
        [userId, r.id, ingredientId, alert.kind, alert.before, alert.after, alert.suggested, cause]);
      void notifyPriceAlert(userId, { recipeId: r.id, name: r.name, kind: alert.kind, before: alert.before, after: alert.after, cause });
      n++;
    }
    return n;
  } catch (e) {
    console.error('[price-impact]', e);
    return 0;
  }
}
