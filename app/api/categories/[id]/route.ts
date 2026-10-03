import { fail, ok, route, str } from '@/lib/api';
import { q, q1 } from '@/lib/db';

export const PATCH = route(async (req, user, { id }) => {
  const b = await req.json();
  const name = str(b.name);
  if (!name) return fail('Dê um nome para a categoria.');
  const row = await q1(
    'UPDATE categories SET name=$3, icon=$4, color=$5 WHERE id=$1 AND user_id=$2 RETURNING *',
    [id, user.id, name.slice(0, 40), str(b.icon) || 'tag', /^#[0-9a-f]{6}$/i.test(str(b.color)) ? str(b.color) : '#e0357a'],
  );
  return row ? ok(row) : fail('Categoria não encontrada.', 404);
});

export const DELETE = route(async (_req, user, { id }) => {
  await q('DELETE FROM categories WHERE id=$1 AND user_id=$2', [id, user.id]);
  return ok();
});
