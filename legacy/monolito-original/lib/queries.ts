import { q, q1 } from './db';
import { calcRecipe } from './pricing';
import { CatKind } from './defaults';

export type Category = { id: number; kind: CatKind; name: string; icon: string; color: string; uses: number };
export type Ingredient = {
  id: number; category_id: number | null; name: string; unit: 'g' | 'ml' | 'un';
  stock_qty: number; package_size: number; package_cost: number; min_stock: number; supplier: string; notes: string;
};
export type Settings = { hourly_rate: number; monthly_units: number };
export type FixedCost = { id: number; category_id: number | null; name: string; kind: 'fixed' | 'variable'; amount: number };
export type Order = {
  id: number; number: number; order_date: string; customer: string; recipe_id: number | null; product: string;
  qty: number; unit_price: number; delivery_date: string | null; status: string; payment: string; notes: string;
};

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
  return (await q1<Settings>('SELECT hourly_rate, monthly_units FROM settings WHERE user_id=$1', [userId])) ?? { hourly_rate: 0, monthly_units: 100 };
}
export const getFixedCosts = (userId: number) => q<FixedCost>('SELECT * FROM fixed_costs WHERE user_id=$1 ORDER BY lower(name)', [userId]);
export async function getFixedTotal(userId: number): Promise<number> {
  return (await q1<{ t: number }>('SELECT COALESCE(SUM(amount),0) t FROM fixed_costs WHERE user_id=$1', [userId]))?.t ?? 0;
}
export const getOrders = (userId: number) =>
  q<Order>('SELECT * FROM orders WHERE user_id=$1 ORDER BY order_date DESC, number DESC', [userId]);

export type RecipeRow = {
  id: number; name: string; category_id: number | null; yield_units: number; margin_pct: number; prep_hours: number; notes: string;
  photo_v: string | null; // versão da foto (muda a cada envio); nulo = sem foto
  items: { ingredient_id: number; qty: number }[];
};
export async function getRecipes(userId: number): Promise<RecipeRow[]> {
  return q<RecipeRow>(
    `SELECT r.id, r.name, r.category_id, r.yield_units, r.margin_pct, r.prep_hours, r.notes,
       (SELECT (extract(epoch from p.updated_at) * 1000)::bigint::text FROM recipe_photos p WHERE p.recipe_id = r.id) AS photo_v,
       COALESCE((SELECT json_agg(json_build_object('ingredient_id', ri.ingredient_id, 'qty', ri.qty::float8) ORDER BY ri.id)
                 FROM recipe_items ri WHERE ri.recipe_id = r.id), '[]') AS items
     FROM recipes r WHERE r.user_id=$1 ORDER BY lower(r.name)`,
    [userId],
  );
}

// Receitas já precificadas (usado na lista de receitas e no painel).
export async function getPricedRecipes(userId: number) {
  const [recipes, ings, settings, fixedTotal] = await Promise.all([getRecipes(userId), getIngredients(userId), getSettings(userId), getFixedTotal(userId)]);
  const cost = new Map(ings.map((i) => [i.id, i.package_size > 0 ? i.package_cost / i.package_size : 0]));
  return recipes.map((r) => ({
    ...r,
    pricing: calcRecipe({
      items: r.items.map((it) => ({ qty: it.qty, unitCost: cost.get(it.ingredient_id) ?? 0 })),
      yieldUnits: r.yield_units, marginPct: r.margin_pct, prepHours: r.prep_hours,
      hourlyRate: settings.hourly_rate, fixedMonthly: fixedTotal, monthlyUnits: settings.monthly_units,
    }),
  }));
}

export type Goal = { revenue: number; orders: number; new_customers: number; avg_ticket: number };
export type MonthStats = { revenue: number; orders: number; avg_ticket: number; new_customers: number };

export async function getGoal(userId: number, month: string): Promise<Goal> {
  return (await q1<Goal>('SELECT revenue, orders, new_customers, avg_ticket FROM goals WHERE user_id=$1 AND month=$2', [userId, month]))
    ?? { revenue: 0, orders: 0, new_customers: 0, avg_ticket: 0 };
}

// Realizado do mês: pedidos não cancelados com data dentro do mês.
export async function getMonthStats(userId: number, month: string): Promise<MonthStats> {
  const r = await q1<{ revenue: number; orders: number; new_customers: number }>(
    `WITH m AS (SELECT * FROM orders WHERE user_id=$1 AND status <> 'cancelado' AND order_date >= $2::date AND order_date < ($2::date + interval '1 month')),
          first_seen AS (SELECT lower(customer) c, MIN(order_date) d FROM orders WHERE user_id=$1 AND status <> 'cancelado' GROUP BY 1)
     SELECT COALESCE((SELECT SUM(qty*unit_price) FROM m),0) AS revenue,
            (SELECT count(*)::int FROM m) AS orders,
            (SELECT count(*)::int FROM first_seen WHERE d >= $2::date AND d < ($2::date + interval '1 month')) AS new_customers`,
    [userId, month],
  );
  const revenue = r?.revenue ?? 0, orders = r?.orders ?? 0;
  return { revenue, orders, avg_ticket: orders > 0 ? revenue / orders : 0, new_customers: r?.new_customers ?? 0 };
}
