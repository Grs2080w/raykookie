import { fail, ok, route } from '@/lib/api';
import { searchPrices } from '@/lib/suppliers';

/** Procura o ingrediente nos sites dos fornecedores: `{ingredient_id, term?}`. */
export const POST = route(async (req, user) => {
  const b = await req.json().catch(() => ({}));
  const r = await searchPrices(user.id, Number(b.ingredient_id), b.term);
  return r ? ok(r) : fail('Ingrediente não encontrado.', 404);
});
