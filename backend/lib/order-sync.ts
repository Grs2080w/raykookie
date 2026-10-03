import { q, q1 } from './db';
import { notifyCustomerStatus } from './push';
import { overallStatus } from '@shared/shop';

/** Mantém `shop_orders.delivered_at` em dia: preenchido quando o último item vivo é entregue, limpo se voltar atrás.
 *  É daqui que sai o prazo de 48h para pedir devolução. */
export async function syncDelivered(shopOrderId: number | null | undefined): Promise<void> {
  if (!shopOrderId) return;
  await q(
    `UPDATE shop_orders so SET delivered_at = CASE
        WHEN EXISTS (SELECT 1 FROM orders o WHERE o.shop_order_id = so.id AND o.status = 'entregue')
         AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.shop_order_id = so.id AND o.status NOT IN ('entregue','cancelado'))
        THEN COALESCE(so.delivered_at, now()) ELSE NULL END
      WHERE so.id = $1`, [shopOrderId]);
}

type Snapshot = { shopOrderId: number; overall: string } | null;

/** Etapa geral do pedido da loja ao qual pertence o item `orderItemId` (antes de mudar o status). */
export async function shopSnapshot(orderItemId: number | string, userId: number): Promise<Snapshot> {
  const r = await q1<{ shop_order_id: number | null; statuses: string[] }>(
    `SELECT o.shop_order_id, (SELECT array_agg(x.status) FROM orders x WHERE x.shop_order_id = o.shop_order_id) AS statuses
       FROM orders o WHERE o.id = $1 AND o.user_id = $2`, [orderItemId, userId]);
  return r?.shop_order_id ? { shopOrderId: r.shop_order_id, overall: overallStatus(r.statuses) } : null;
}

/** Depois de mudar o status de um item: atualiza a entrega e, se a etapa geral do pedido mudou, avisa a cliente. */
export async function afterStatusChange(before: Snapshot): Promise<void> {
  if (!before) return;
  await syncDelivered(before.shopOrderId);
  const r = await q1<{ statuses: string[]; customer_id: number; code: number; total: number; fulfillment: string; time_window: string; paid: boolean }>(
    `SELECT (SELECT array_agg(o.status) FROM orders o WHERE o.shop_order_id = so.id) AS statuses, so.customer_id, so.public_code AS code, so.total::float8 AS total,
            so.fulfillment, so.time_window, (so.paid_at IS NOT NULL) AS paid
       FROM shop_orders so WHERE so.id = $1`, [before.shopOrderId]);
  if (!r) return;
  const now = overallStatus(r.statuses);
  if (now !== before.overall) {
    void notifyCustomerStatus({ id: before.shopOrderId, code: r.code, customerId: r.customer_id, total: r.total, fulfillment: r.fulfillment, timeWindow: r.time_window, paid: r.paid }, now);
  }
}
