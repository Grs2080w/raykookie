import { getIngredients } from '@/lib/queries';
import { fail, num, ok, route, str } from '@/lib/api';
import { q1 } from '@/lib/db';
import { parseIngredient } from '@/lib/ingredient-input';

export const POST = route(async (req, user) => {
  const p = parseIngredient(await req.json());
  if (p.error) return fail(p.error);
  const row = await q1(
    `INSERT INTO ingredients(user_id, category_id, name, unit, stock_qty, package_size, package_cost, min_stock, supplier, notes)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING *`,
    [user.id, ...p.value!],
  );
  return ok(row, 201);
});

export const GET = route(async (_req, user) => ok(await getIngredients(user.id)));
