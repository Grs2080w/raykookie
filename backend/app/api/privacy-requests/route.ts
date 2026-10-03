import { ok, route } from '@/lib/api';
import { q } from '@/lib/db';
import { PRIVACY_DEADLINE_DAYS } from '@shared/lgpd';

/** Solicitações de privacidade dos clientes, com o prazo legal. */
export const GET = route(async (_req, user) => {
  const rows = await q(
    `SELECT r.id, r.type, r.details, r.status, r.answer, r.created_at, r.answered_at,
            c.name AS customer, c.email, (r.created_at + make_interval(days => $2)) AS due_at
       FROM privacy_requests r JOIN customers c ON c.id = r.customer_id
      WHERE r.owner_id = $1 ORDER BY (r.status = 'aberta') DESC, r.created_at DESC LIMIT 100`,
    [user.id, PRIVACY_DEADLINE_DAYS],
  );
  return ok(rows);
});
