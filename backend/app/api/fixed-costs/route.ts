import { getFixedCosts } from '@/lib/queries';
import { fail, num, ok, route, str } from '@/lib/api';
import { q1 } from '@/lib/db';

export const POST = route(async (req, user) => {
  const b = await req.json();
  const name = str(b.name);
  if (!name) return fail('Dê um nome para o gasto.');
  const row = await q1(
    'INSERT INTO fixed_costs(user_id, category_id, name, kind, amount) VALUES ($1,$2,$3,$4,$5) RETURNING *',
    [user.id, b.category_id ? Number(b.category_id) : null, name.slice(0, 80), b.kind === 'variable' ? 'variable' : 'fixed', Math.max(0, num(b.amount))],
  );
  return ok(row, 201);
});

export const GET = route(async (_req, user) => ok(await getFixedCosts(user.id)));
