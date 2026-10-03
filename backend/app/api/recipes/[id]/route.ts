import { getRecipes } from '@/lib/queries';
import { fail, ok, route } from '@/lib/api';
import { q } from '@/lib/db';
import { saveRecipe } from '@/lib/recipe-save';

export const PUT = route(async (req, user, { id }) => {
  const r = await saveRecipe(user.id, Number(id), await req.json());
  return r.error ? fail(r.error, r.status) : ok(r.row);
});
export const DELETE = route(async (_req, user, { id }) => {
  await q('DELETE FROM recipes WHERE id=$1 AND user_id=$2', [id, user.id]);
  return ok();
});

export const GET = route(async (_req, user, { id }) => {
  const r = (await getRecipes(user.id)).find((x) => x.id === Number(id));
  return r ? ok(r) : fail('Receita não encontrada.', 404);
});
