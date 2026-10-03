import { ok, route } from '@/lib/api';
import { q } from '@/lib/db';

/** Marca o alerta como lido (some do painel). */
export const POST = route(async (_req, user, { id }) => {
  await q('UPDATE price_alerts SET read = true WHERE id = $1 AND user_id = $2', [Number(id), user.id]);
  return ok();
});
