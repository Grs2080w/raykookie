import { fail, isoDate, num, ok, route, str } from '@/lib/api';
import { q1 } from '@/lib/db';

export const PUT = route(async (req, user) => {
  const b = await req.json();
  const month = str(b.month).endsWith('-01') ? isoDate(b.month) : null;
  if (!month) return fail('Mês inválido.');
  const row = await q1(
    `INSERT INTO goals(user_id, month, revenue, orders, new_customers, avg_ticket) VALUES ($1,$2,$3,$4,$5,$6)
     ON CONFLICT (user_id, month) DO UPDATE SET revenue=EXCLUDED.revenue, orders=EXCLUDED.orders, new_customers=EXCLUDED.new_customers, avg_ticket=EXCLUDED.avg_ticket
     RETURNING *`,
    [user.id, month, Math.max(0, num(b.revenue)), Math.max(0, Math.round(num(b.orders))), Math.max(0, Math.round(num(b.new_customers))), Math.max(0, num(b.avg_ticket))],
  );
  return ok(row);
});
