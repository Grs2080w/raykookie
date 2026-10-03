import { fail, ok, route } from '@/lib/api';
import { ingredientPrices } from '@/lib/suppliers';

export const GET = route(async (_req, user, { id }) => {
  const r = await ingredientPrices(user.id, Number(id));
  return r ? ok(r) : fail('Ingrediente não encontrado.', 404);
});
