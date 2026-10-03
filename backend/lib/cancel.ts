import { q, q1, tx } from './db';
import { cancelPayment, getPayment } from './mercadopago';
import { notifyCancelled } from './push';
import { cancelAndRefund } from './refunds';
import { logEvent } from './order-events';
import { anyReasonLabel } from '@shared/refund';

export type CancelResult = { ok: true; state: 'cancelled' | 'already'; refunded?: number } | { ok: false; status: number; error: string };

/**
 * O cliente cancela o próprio pedido. Só enquanto ele ainda é "novo" (a confeiteira não começou a produzir) e não foi pago:
 * pedido já pago ou em produção segue pelo atendimento (a devolução de Pix é feita por lá).
 * Se havia um Pix pendente, ele é desativado no Mercado Pago, para ninguém pagar um pedido cancelado.
 */
export async function cancelShopOrder(orderId: number, customerId: number, reason: string, code = 'outro'): Promise<CancelResult> {
  const row = await q1<{ id: number; code: number; owner_id: number; contact_name: string; total: number; payment_method: string; paid_at: Date | null; mp_payment_id: string | null }>(
    'SELECT id, public_code AS code, owner_id, contact_name, total, payment_method, paid_at, mp_payment_id FROM shop_orders WHERE id = $1 AND customer_id = $2',
    [orderId, customerId],
  );
  if (!row) return { ok: false, status: 404, error: 'Pedido não encontrado.' };
  // pedido pago: só cancela se a produção não começou, e o Pix é devolvido na hora
  if (row.paid_at) {
    const r = await cancelAndRefund(orderId, customerId, code, reason);
    return r.ok ? { ok: true, state: 'cancelled', refunded: r.refunded } : r;
  }

  // Pix pendente: confere se não foi pago agora e, se não foi, desativa o código antes de cancelar
  if (row.payment_method === 'pix' && row.mp_payment_id) {
    try {
      const p = await getPayment(row.mp_payment_id);
      if (p.status === 'approved') {
        await q(`UPDATE shop_orders SET paid_at = now(), paid_mp_id = $2 WHERE id = $1 AND paid_at IS NULL`, [orderId, String(p.id)]);
        await q(`UPDATE orders SET payment = 'pago' WHERE shop_order_id = $1`, [orderId]);
        const r = await cancelAndRefund(orderId, customerId, code, reason); // o pagamento entrou agora: cancela e devolve
        return r.ok ? { ok: true, state: 'cancelled', refunded: r.refunded } : r;
      }
      if (p.status === 'pending' || p.status === 'in_process') await cancelPayment(row.mp_payment_id);
    } catch { /* sem resposta do Mercado Pago: o código vence sozinho e o pedido fica cancelado de qualquer forma */ }
  }

  const outcome = await tx(async (c) => {
    const { rows } = await c.query('SELECT status FROM orders WHERE shop_order_id = $1 FOR UPDATE', [orderId]); // trava: a confeiteira não muda o status no meio
    const statuses = rows.map((r: { status: string }) => r.status);
    if (statuses.length > 0 && statuses.every((s: string) => s === 'cancelado')) return 'already' as const;
    if (!statuses.every((s: string) => s === 'novo' || s === 'cancelado')) return 'started' as const;
    await c.query(`UPDATE orders SET status = 'cancelado' WHERE shop_order_id = $1 AND status = 'novo'`, [orderId]);
    await c.query("UPDATE shop_orders SET cancelled_at = now(), cancel_reason = $2, cancel_reason_code = $3, cancelled_by = 'cliente', cancel_note = $4 WHERE id = $1", [orderId, anyReasonLabel(code).slice(0, 200), code, reason.slice(0, 300)]);
    await logEvent(orderId, 'cliente', 'cancelado', { code, note: reason, paid: false }, c);
    return 'cancelled' as const;
  });
  if (outcome === 'started') return { ok: false, status: 409, error: 'Seu pedido já entrou em produção e não pode mais ser cancelado por aqui. Fale com a gente.' };
  if (outcome === 'cancelled') void notifyCancelled(row.owner_id, { id: orderId, code: row.code, customer: row.contact_name, total: row.total, reason: [code === 'outro' && reason ? '' : anyReasonLabel(code), reason].filter(Boolean).join(': ') }); // sem await: o cliente não espera o push
  return { ok: true, state: outcome };
}
