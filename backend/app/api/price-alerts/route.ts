import { ok, route } from '@/lib/api';
import { q } from '@/lib/db';

/** Alertas de margem/preço ainda não lidos (mais novos primeiro). */
export const GET = route(async (_req, user) => ok(await q(
  `SELECT a.id, a.recipe_id, r.name AS recipe, a.kind, a.before_value::float8 AS before_value, a.after_value::float8 AS after_value, a.suggested::float8 AS suggested, a.cause, a.created_at
     FROM price_alerts a JOIN recipes r ON r.id = a.recipe_id WHERE a.user_id = $1 AND NOT a.read ORDER BY a.created_at DESC LIMIT 50`, [user.id])));
