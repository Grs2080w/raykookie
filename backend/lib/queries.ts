import { q, q1 } from './db';
import { calcRecipe, withSalePrice } from '@shared/pricing';
import type { CatKind } from '@shared/types';

export type { Category, Ingredient, Settings, FixedCost, Order, RecipeRow, PricedRecipe, Goal, MonthStats } from '@shared/types';
import type { Category, Ingredient, Settings, FixedCost, Order, RecipeRow, PricedRecipe, Goal, MonthStats } from '@shared/types';

export async function getCategories(userId: number, kind?: CatKind): Promise<Category[]> {
  return q<Category>(
    `SELECT c.id, c.kind, c.name, c.icon, c.color,
       (SELECT count(*)::int FROM ingredients i WHERE i.category_id = c.id)
     + (SELECT count(*)::int FROM recipes r WHERE r.category_id = c.id)
     + (SELECT count(*)::int FROM fixed_costs f WHERE f.category_id = c.id) AS uses
     FROM categories c WHERE c.user_id=$1 ${kind ? 'AND c.kind=$2' : ''} ORDER BY c.kind, lower(c.name)`,
    kind ? [userId, kind] : [userId],
  );
}
export const getIngredients = (userId: number) => q<Ingredient>('SELECT * FROM ingredients WHERE user_id=$1 ORDER BY lower(name)', [userId]);
export async function getSettings(userId: number): Promise<Settings> {
  return (await q1<Settings>('SELECT hourly_rate, monthly_units, min_margin_pct FROM settings WHERE user_id=$1', [userId])) ?? { hourly_rate: 0, monthly_units: 100, min_margin_pct: 30 };
}
export const getFixedCosts = (userId: number) => q<FixedCost>('SELECT * FROM fixed_costs WHERE user_id=$1 ORDER BY lower(name)', [userId]);
export async function getFixedTotal(userId: number): Promise<number> {
  return (await q1<{ t: number }>('SELECT COALESCE(SUM(amount),0) t FROM fixed_costs WHERE user_id=$1', [userId]))?.t ?? 0;
}
export const getOrders = (userId: number) =>
  q<Order>(
    `SELECT o.*, so.public_code AS shop_code, (so.paid_at IS NOT NULL) AS shop_paid, so.total::float8 AS shop_total, so.refunded_total::float8 AS shop_refunded,
            so.payment_method AS shop_payment_method, so.installments AS shop_installments,
            CASE WHEN so.cancelled_at IS NOT NULL THEN json_build_object('by', so.cancelled_by, 'code', so.cancel_reason_code, 'reason', so.cancel_reason, 'note', so.cancel_note) END AS shop_cancel,
            (SELECT json_build_object('id', r.id, 'kind', r.kind, 'status', r.status, 'reason_code', r.reason_code, 'requested_amount', r.requested_amount::float8,
                                      'approved_amount', r.approved_amount::float8, 'fail_reason', r.fail_reason, 'decision_code', r.decision_code,
                                      'photos', (SELECT count(*)::int FROM refund_photos p WHERE p.refund_id = r.id))
               FROM refunds r WHERE r.shop_order_id = so.id AND r.status <> 'desistiu' ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS shop_refund
       FROM orders o LEFT JOIN shop_orders so ON so.id = o.shop_order_id WHERE o.user_id=$1 ORDER BY o.order_date DESC, o.number DESC`, [userId]);

export async function getRecipes(userId: number): Promise<RecipeRow[]> {
  return q<RecipeRow>(
    `SELECT r.id, r.name, r.category_id, r.yield_units, r.margin_pct, r.prep_hours, r.notes, r.unit_weight_g::float8 AS unit_weight_g, r.sale_price::float8 AS sale_price,
       r.show_on_menu, r.tags,
       (SELECT (extract(epoch from p.updated_at) * 1000)::bigint::text FROM recipe_photos p WHERE p.recipe_id = r.id) AS photo_v,
       COALESCE((SELECT json_agg(json_build_object('ingredient_id', ri.ingredient_id, 'qty', ri.qty::float8) ORDER BY ri.id)
                 FROM recipe_items ri WHERE ri.recipe_id = r.id), '[]') AS items
     FROM recipes r WHERE r.user_id=$1 ORDER BY lower(r.name)`,
    [userId],
  );
}

// Receitas já precificadas (usado na lista de receitas e no painel).
export async function getPricedRecipes(userId: number): Promise<PricedRecipe[]> {
  const [recipes, ings, settings, fixedTotal] = await Promise.all([getRecipes(userId), getIngredients(userId), getSettings(userId), getFixedTotal(userId)]);
  const cost = new Map(ings.map((i) => [i.id, i.package_size > 0 ? i.package_cost / i.package_size : 0]));
  return recipes.map((r) => ({
    ...r,
    pricing: withSalePrice(calcRecipe({
      items: r.items.map((it) => ({ qty: it.qty, unitCost: cost.get(it.ingredient_id) ?? 0 })),
      yieldUnits: r.yield_units, marginPct: r.margin_pct, prepHours: r.prep_hours,
      hourlyRate: settings.hourly_rate, fixedMonthly: fixedTotal, monthlyUnits: settings.monthly_units,
    }), r.sale_price),
  }));
}


export async function getGoal(userId: number, month: string): Promise<Goal> {
  return (await q1<Goal>('SELECT revenue, orders, new_customers, avg_ticket FROM goals WHERE user_id=$1 AND month=$2', [userId, month]))
    ?? { revenue: 0, orders: 0, new_customers: 0, avg_ticket: 0 };
}

// Realizado do mês: pedidos não cancelados com data dentro do mês, menos o que foi devolvido por Pix.
// Itens cancelados já ficam de fora; da devolução só sai o que passa do valor desses itens (senão descontaria duas vezes).
export async function getMonthStats(userId: number, month: string): Promise<MonthStats> {
  const r = await q1<{ revenue: number; refunded: number; orders: number; new_customers: number }>(
    `WITH m AS (SELECT * FROM orders WHERE user_id=$1 AND status <> 'cancelado' AND order_date >= $2::date AND order_date < ($2::date + interval '1 month')),
          first_seen AS (SELECT lower(customer) c, MIN(order_date) d FROM orders WHERE user_id=$1 AND status <> 'cancelado' GROUP BY 1),
          refunded AS (
            SELECT GREATEST(0, so.refunded_total - COALESCE((SELECT SUM(o.qty*o.unit_price) FROM orders o WHERE o.shop_order_id = so.id AND o.status = 'cancelado'), 0)) AS v
              FROM shop_orders so WHERE so.refunded_total > 0 AND so.id IN (SELECT shop_order_id FROM m WHERE shop_order_id IS NOT NULL))
     SELECT COALESCE((SELECT SUM(qty*unit_price) FROM m),0)::float8 AS revenue,
            COALESCE((SELECT SUM(v) FROM refunded),0)::float8 AS refunded,
            (SELECT count(*)::int FROM m) AS orders,
            (SELECT count(*)::int FROM first_seen WHERE d >= $2::date AND d < ($2::date + interval '1 month')) AS new_customers`,
    [userId, month],
  );
  const refunded = r?.refunded ?? 0;
  const revenue = Math.max(0, (r?.revenue ?? 0) - refunded), orders = r?.orders ?? 0;
  return { revenue, profit: (await getMonthProfit(userId, month)) - refunded, orders, avg_ticket: orders > 0 ? revenue / orders : 0, new_customers: r?.new_customers ?? 0 };
}

// Lucro do mês: unidades pedidas de cada receita × lucro unitário da receita (pedidos sem receita não têm custo conhecido).
async function getMonthProfit(userId: number, month: string): Promise<number> {
  const sold = await q<{ recipe_id: number; units: number }>(
    `SELECT recipe_id, SUM(qty)::float8 AS units FROM orders
     WHERE user_id=$1 AND status <> 'cancelado' AND recipe_id IS NOT NULL AND order_date >= $2::date AND order_date < ($2::date + interval '1 month')
     GROUP BY recipe_id`,
    [userId, month],
  );
  if (sold.length === 0) return 0;
  const profit = new Map((await getPricedRecipes(userId)).map((r) => [r.id, r.pricing.profitPerUnit]));
  return sold.reduce((sum, s) => sum + s.units * (profit.get(s.recipe_id) ?? 0), 0);
}
