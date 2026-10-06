// Pagamento com cartão: mesmo formato de pix.ts, mas a aprovação chega na hora (não precisa esperar o webhook).
import { q, q1, tx } from './db';
import { cardFailReason, createCardPayment, mpCardConfigured } from './mercadopago';
import { logEvent } from './order-events';
import { notifyCustomerPaid, notifyPaid } from './push';

export const MAX_CARD_ATTEMPTS = 5; // tentativas por pedido (evita testar cartão roubado contra um pedido real)

export type CardPayResult =
  | { ok: true; approved: true }
  | { ok: true; approved: false; pending: true }
  | { ok: true; approved: false; pending: false; reason: string }
  | { ok: false; status: number; error: string };

/** Cobra o cartão contra um pedido já criado (`external_reference` = id do pedido, igual ao Pix). Se aprovado,
 *  marca pago e avisa a confeiteira e a cliente na mesma chamada — o webhook que chegar depois é um no-op
 *  idempotente (`WHERE paid_at IS NULL`). Se recusado, o pedido continua existindo, sem pagamento (como um
 *  Pix nunca pago): quem chama decide se oferece tentar outro cartão. */
export async function payWithCard(o: {
  orderId: number; code: number; total: number; email: string; name: string; cpf: string;
  cardToken: string; paymentMethodId: string; installments: number; ownerId: number; customerId: number; items: number; attempt: number;
}): Promise<CardPayResult> {
  if (!mpCardConfigured()) return { ok: false, status: 503, error: 'O pagamento com cartão não está disponível agora.' };
  let pay;
  try {
    pay = await createCardPayment({
      orderId: o.orderId, code: o.code, total: o.total, email: o.email, name: o.name, cpf: o.cpf,
      cardToken: o.cardToken, paymentMethodId: o.paymentMethodId, installments: o.installments, attempt: o.attempt,
    });
  } catch (e) {
    console.error('[mercadopago] falha ao cobrar o cartão', o.orderId, e);
    return { ok: false, status: 502, error: 'Não conseguimos falar com o Mercado Pago agora. Tente de novo ou pague com Pix.' };
  }

  if (pay.status === 'approved') {
    const paidNow = await tx(async (c) => {
      const { rowCount } = await c.query(
        `UPDATE shop_orders SET paid_at = now(), paid_mp_id = $2, installments = $3, mp_payment_ids = array_append(mp_payment_ids, $2)
           WHERE id = $1 AND $4::numeric >= total AND paid_at IS NULL`,
        [o.orderId, pay.paymentId, o.installments, o.total],
      );
      if (rowCount) {
        await c.query(`UPDATE orders SET payment = 'pago' WHERE shop_order_id = $1`, [o.orderId]);
        await logEvent(o.orderId, 'mercadopago', 'pago', { payment: pay.paymentId, amount: o.total, via: 'cartao', installments: o.installments }, c);
      }
      return !!rowCount;
    });
    if (paidNow) {
      void notifyPaid(o.ownerId, { id: o.orderId, code: o.code, customer: o.name, total: o.total, items: o.items });
      void notifyCustomerPaid({ id: o.orderId, code: o.code, customerId: o.customerId, total: o.total });
    }
    return { ok: true, approved: true };
  }
  if (pay.status === 'pending' || pay.status === 'in_process') {
    await q(`UPDATE shop_orders SET mp_payment_ids = array_append(mp_payment_ids, $2), card_attempts = card_attempts + 1 WHERE id = $1`, [o.orderId, pay.paymentId]);
    return { ok: true, approved: false, pending: true };
  }
  await q(`UPDATE shop_orders SET card_attempts = card_attempts + 1 WHERE id = $1`, [o.orderId]);
  return { ok: true, approved: false, pending: false, reason: cardFailReason(pay.statusDetail) };
}

/** "Tentar outro cartão": o pedido já existe (criado sem pagar, ou com um cartão recusado antes). */
export async function retryCard(orderId: number, customerId: number, b: { cardToken: string; paymentMethodId: string; installments: number; cpf: string }): Promise<CardPayResult> {
  const row = await q1<{
    owner_id: number; code: number; total: number; contact_name: string; payment_method: string;
    paid_at: Date | null; card_attempts: number; email: string; statuses: string[] | null; n: number;
  }>(
    `SELECT so.owner_id, so.public_code AS code, so.total::float8 AS total, so.contact_name, so.payment_method, so.paid_at, so.card_attempts, c.email,
            jsonb_array_length(so.items)::int AS n, (SELECT array_agg(o.status) FROM orders o WHERE o.shop_order_id = so.id) AS statuses
       FROM shop_orders so JOIN customers c ON c.id = so.customer_id WHERE so.id = $1 AND so.customer_id = $2`,
    [orderId, customerId],
  );
  if (!row) return { ok: false, status: 404, error: 'Pedido não encontrado.' };
  if (row.payment_method !== 'cartao') return { ok: false, status: 400, error: 'Este pedido não é pago com cartão.' };
  if (row.paid_at) return { ok: true, approved: true };
  const live = (row.statuses ?? []).filter((s) => s && s !== 'cancelado');
  if (live.length === 0) return { ok: false, status: 400, error: 'Este pedido foi cancelado.' };
  if (row.card_attempts >= MAX_CARD_ATTEMPTS) return { ok: false, status: 429, error: 'Muitas tentativas de cartão para este pedido. Tente pagar com Pix ou fale com a gente.' };

  return payWithCard({
    orderId, code: row.code, total: row.total, email: row.email, name: row.contact_name, cpf: b.cpf,
    cardToken: b.cardToken, paymentMethodId: b.paymentMethodId, installments: b.installments,
    ownerId: row.owner_id, customerId, items: row.n, attempt: row.card_attempts + 1,
  });
}
