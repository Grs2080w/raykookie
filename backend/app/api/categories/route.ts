import { getCategories } from '@/lib/queries';
import { fail, ok, route, str } from '@/lib/api';
import { q1 } from '@/lib/db';

export const POST = route(async (req, user) => {
  const b = await req.json();
  const kind = str(b.kind), name = str(b.name);
  if (!['ingredient', 'recipe', 'expense'].includes(kind)) return fail('Tipo de categoria inválido.');
  if (!name) return fail('Dê um nome para a categoria.');
  const row = await q1(
    'INSERT INTO categories(user_id, kind, name, icon, color) VALUES ($1,$2,$3,$4,$5) RETURNING *',
    [user.id, kind, name.slice(0, 40), str(b.icon) || 'tag', /^#[0-9a-f]{6}$/i.test(str(b.color)) ? str(b.color) : '#e0357a'],
  );
  return ok(row, 201);
});

export const GET = route(async (req, user) => {
  const kind = new URL(req.url).searchParams.get('kind');
  return ok(await getCategories(user.id, kind === 'ingredient' || kind === 'recipe' || kind === 'expense' ? kind : undefined));
});
