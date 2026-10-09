import { fail, num, ok, route, str } from '@/lib/api';
import { q1 } from '@/lib/db';
import { parseOrder } from '@/lib/order-input';

export const POST = route(async (req, user) => {
  const p = parseOrder(await req.json());
  if (p.error) return fail(p.error);
  const row = await q1(
    `INSERT INTO orders(user_id, number, order_date, customer, recipe_id, product, qty, unit_price, delivery_date, status, payment, notes)
     VALUES ($1, (SELECT COALESCE(MAX(number),0)+1 FROM orders WHERE user_id=$1), $2,$3,$4,$5,$6,$7,$8,$9,$10,$11) RETURNING *`,
    [user.id, ...p.value!],
  );
  return ok(row, 201);
});
