import { fail, ok, route, str } from '@/lib/api';
import { q, q1 } from '@/lib/db';
import { parseOrder } from '@/lib/order-input';

export const PATCH = route(async (req, user, { id }) => {
  const b = await req.json();
  // troca rápida de status/pagamento
  if ('status' in b || 'payment' in b) {
    if (Object.keys(b).length <= 2 && !('customer' in b)) {
      const row = await q1(
        `UPDATE orders SET status=COALESCE($3,status), payment=COALESCE($4,payment) WHERE id=$1 AND user_id=$2 RETURNING *`,
        [id, user.id, b.status ? str(b.status) : null, b.payment ? str(b.payment) : null],
      );
      return row ? ok(row) : fail('Pedido não encontrado.', 404);
    }
  }
  const p = parseOrder(b);
  if (p.error) return fail(p.error);
  const row = await q1(
    `UPDATE orders SET order_date=$3, customer=$4, recipe_id=$5, product=$6, qty=$7, unit_price=$8, delivery_date=$9, status=$10, payment=$11, notes=$12
     WHERE id=$1 AND user_id=$2 RETURNING *`,
    [id, user.id, ...p.value!],
  );
  return row ? ok(row) : fail('Pedido não encontrado.', 404);
});
export const DELETE = route(async (_req, user, { id }) => {
  await q('DELETE FROM orders WHERE id=$1 AND user_id=$2', [id, user.id]);
  return ok();
});
