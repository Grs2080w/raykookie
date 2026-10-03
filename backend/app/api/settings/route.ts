import { getSettings } from '@/lib/queries';
import { fail, num, ok, route } from '@/lib/api';
import { q1 } from '@/lib/db';

export const PUT = route(async (req, user) => {
  const b = await req.json();
  const units = num(b.monthly_units, 100);
  if (units <= 0) return fail('Informe quantas unidades você produz por mês (maior que zero).');
  const row = await q1(
    `INSERT INTO settings(user_id, hourly_rate, monthly_units, min_margin_pct) VALUES ($1,$2,$3,$4)
     ON CONFLICT (user_id) DO UPDATE SET hourly_rate=EXCLUDED.hourly_rate, monthly_units=EXCLUDED.monthly_units, min_margin_pct=COALESCE($5, settings.min_margin_pct) RETURNING *`,
    [user.id, Math.max(0, num(b.hourly_rate)), units, Math.min(95, Math.max(0, num(b.min_margin_pct, 30))), b.min_margin_pct == null || b.min_margin_pct === '' ? null : Math.min(95, Math.max(0, num(b.min_margin_pct)))],
  );
  return ok(row);
});

export const GET = route(async (_req, user) => ok(await getSettings(user.id)));
