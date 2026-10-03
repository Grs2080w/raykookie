import { ok, route } from '@/lib/api';
import { q } from '@/lib/db';

// Lista da confeiteira. ?status=aguardando|andamento|concluidas|negadas (padrão: aguardando)
const GROUPS: Record<string, string[]> = {
  aguardando: ['solicitado'],
  andamento: ['aprovado', 'processando', 'falhou'],
  concluidas: ['concluido'],
  negadas: ['negado', 'desistiu'],
};

export const GET = route(async (req, user) => {
  const g = new URL(req.url).searchParams.get('status') ?? 'aguardando';
  const rows = await q(
    `SELECT r.id, r.shop_order_id AS order_id, so.public_code AS code, so.contact_name AS customer, r.kind, r.status, r.reason_code, r.priority,
            r.requested_amount::float8 AS requested_amount, r.approved_amount::float8 AS approved_amount, r.fail_reason, r.created_at,
            (SELECT count(*)::int FROM refund_photos p WHERE p.refund_id = r.id) AS photos
       FROM refunds r JOIN shop_orders so ON so.id = r.shop_order_id
      WHERE r.owner_id = $1 AND r.status = ANY($2)
      ORDER BY r.priority DESC, r.created_at DESC LIMIT 100`, [user.id, GROUPS[g] ?? GROUPS.aguardando]);
  const counts = await q<{ status: string; n: number }>(`SELECT status, count(*)::int AS n FROM refunds WHERE owner_id = $1 GROUP BY status`, [user.id]);
  const by = (list: string[]) => counts.filter((c) => list.includes(c.status)).reduce((s, c) => s + c.n, 0);
  const pending = await q<{ s: number }>(`SELECT COALESCE(sum(approved_amount),0)::float8 AS s FROM refunds WHERE owner_id = $1 AND status IN ('aprovado','processando','falhou')`, [user.id]);
  return ok({ list: rows, counts: Object.fromEntries(Object.entries(GROUPS).map(([k, v]) => [k, by(v)])), pending_amount: pending[0]?.s ?? 0 });
});
