import { fail, ok, route } from '@/lib/api';
import { q, q1 } from '@/lib/db';
import { orderEvents } from '@/lib/order-events';
import { anyReasonLabel } from '@shared/refund';

export const GET = route(async (_req, user, { id }) => {
  const r = await q1<any>(
    `SELECT r.id, r.shop_order_id AS order_id, so.public_code AS code, so.contact_name AS customer, so.phone, so.total::float8 AS order_total, so.refunded_total::float8 AS refunded_total,
            so.items AS order_items, so.delivered_at, so.paid_at, r.kind, r.status, r.reason_code, r.description, r.items, r.priority,
            r.requested_amount::float8 AS requested_amount, r.approved_amount::float8 AS approved_amount, r.decision_code, r.decision_note, r.decided_at,
            r.fail_reason, r.attempts, r.next_try_at, r.created_at, r.refunded_at,
            (SELECT array_agg(o.status) FROM orders o WHERE o.shop_order_id = so.id) AS statuses
       FROM refunds r JOIN shop_orders so ON so.id = r.shop_order_id WHERE r.id = $1 AND r.owner_id = $2`, [Number(id), user.id]);
  if (!r) return fail('Solicitação não encontrada.', 404);
  const photos = await q<{ idx: number; size: number }>('SELECT idx, size FROM refund_photos WHERE refund_id = $1 ORDER BY idx', [r.id]);
  return ok({ ...r, reason: anyReasonLabel(r.reason_code), decision_reason: r.decision_code ? anyReasonLabel(r.decision_code) : '', photos: photos.map((p) => ({ n: p.idx, url: `/api/devolucoes/${r.id}/fotos/${p.idx}` })), events: await orderEvents(r.order_id) });
});
