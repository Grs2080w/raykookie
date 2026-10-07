import { fail, num, ok, route } from '@/lib/api';
import { q1 } from '@/lib/db';

export const PUT = route(async (req, user) => {
  const b = await req.json();
  const units = num(b.monthly_units, 100);
  if (units <= 0) return fail('Informe quantas unidades você produz por mês (maior que zero).');
  const row = await q1(
    `INSERT INTO settings(user_id, hourly_rate, monthly_units) VALUES ($1,$2,$3)
     ON CONFLICT (user_id) DO UPDATE SET hourly_rate=EXCLUDED.hourly_rate, monthly_units=EXCLUDED.monthly_units RETURNING *`,
    [user.id, Math.max(0, num(b.hourly_rate)), units],
  );
  return ok(row);
});
