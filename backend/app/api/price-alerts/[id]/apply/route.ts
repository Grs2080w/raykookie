import { fail, ok, route } from '@/lib/api';
import { q1 } from '@/lib/db';

/** "Aplicar preço sugerido": fixa o preço do cardápio da receita no valor sugerido do alerta e marca como lido. */
export const POST = route(async (_req, user, { id }) => {
  const a = await q1<{ recipe_id: number; suggested: number | null }>('SELECT recipe_id, suggested::float8 AS suggested FROM price_alerts WHERE id = $1 AND user_id = $2', [Number(id), user.id]);
  if (!a || !(a.suggested && a.suggested > 0)) return fail('Alerta não encontrado.', 404);
  await q1('UPDATE recipes SET sale_price = $2 WHERE id = $1 AND user_id = $3', [a.recipe_id, a.suggested, user.id]);
  await q1('UPDATE price_alerts SET read = true WHERE id = $1 AND user_id = $2', [Number(id), user.id]);
  return ok({ ok: true, sale_price: a.suggested });
});
