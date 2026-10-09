import { fail, num, ok, route, str } from '@/lib/api';
import { q, q1 } from '@/lib/db';

export const PATCH = route(async (req, user, { id }) => {
  const b = await req.json();
  const name = str(b.name);
  if (!name) return fail('Dê um nome para o gasto.');
  const row = await q1(
    'UPDATE fixed_costs SET category_id=$3, name=$4, kind=$5, amount=$6 WHERE id=$1 AND user_id=$2 RETURNING *',
    [id, user.id, b.category_id ? Number(b.category_id) : null, name.slice(0, 80), b.kind === 'variable' ? 'variable' : 'fixed', Math.max(0, num(b.amount))],
  );
  return row ? ok(row) : fail('Gasto não encontrado.', 404);
});
export const DELETE = route(async (_req, user, { id }) => {
  await q('DELETE FROM fixed_costs WHERE id=$1 AND user_id=$2', [id, user.id]);
  return ok();
});
