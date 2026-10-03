import { getPricedRecipes } from '@/lib/queries';
import { fail, num, ok, route, str } from '@/lib/api';
import { saveRecipe } from '@/lib/recipe-save';

export const POST = route(async (req, user) => {
  const r = await saveRecipe(user.id, null, await req.json());
  return r.error ? fail(r.error, r.status) : ok(r.row, 201);
});

// Receitas já precificadas (custo, preço sugerido, margem).
export const GET = route(async (_req, user) => ok(await getPricedRecipes(user.id)));
