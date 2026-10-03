import { q, q1 } from './db';
import { cancelPayment, createPixPayment, getPayment, mpConfigured, pixExpiresMinutes } from './mercadopago';

export const MAX_PIX_ATTEMPTS = 10; // Pix gerados por pedido (evita abuso)

type PixRow = {
  id: number; code: number; total: number; contact_name: string; payment_method: string; paid_at: Date | null;
  mp_payment_id: string | null; pix_qr_code: string; pix_expires_at: Date | null; pix_attempts: number;
  statuses: string[] | null; email: string;
};

/** Gera um Pix para o pedido (primeiro ou novo) e grava. Devolve a validade, ou lança erro se o Mercado Pago falhar. */
export async function issuePix(orderId: number, code: number, total: number, email: string, name: string, attempt: number) {
  const expiresAt = new Date(Date.now() + pixExpiresMinutes() * 60_000);
  const pay = await createPixPayment({ orderId, code, total, email, name, attempt, expiresAt });
  await q(
    `UPDATE shop_orders SET mp_payment_id=$2, pix_qr_code=$3, pix_qr_base64=$4, pix_expires_at=$5, pix_attempts=$6,
       mp_payment_ids = array_append(mp_payment_ids, $2) WHERE id=$1`,
    [orderId, pay.paymentId, pay.code, pay.qrBase64, expiresAt, attempt],
  );
  return pay;
}

export type RegenResult =
  | { ok: true; state: 'paid' }
  | { ok: true; state: 'active' | 'renewed'; pix: { code: string; qr_base64: string; expires_in: number } }
  | { ok: false; status: number; error: string };

/** "Gerar novo Pix": só troca o código depois que o atual venceu (e se o atual não foi pago). */
export async function regeneratePix(orderId: number, customerId: number): Promise<RegenResult> {
  if (!mpConfigured()) return { ok: false, status: 503, error: 'O Pix não está disponível agora. Tente de novo mais tarde.' };
  const row = await q1<PixRow>(
    `SELECT so.id, so.public_code AS code, so.total, so.contact_name, so.payment_method, so.paid_at, so.mp_payment_id, so.pix_qr_code, so.pix_expires_at, so.pix_attempts,
            c.email, (SELECT array_agg(o.status) FROM orders o WHERE o.shop_order_id = so.id) AS statuses
     FROM shop_orders so JOIN customers c ON c.id = so.customer_id WHERE so.id = $1 AND so.customer_id = $2`,
    [orderId, customerId],
  );
  if (!row) return { ok: false, status: 404, error: 'Pedido não encontrado.' };
  if (row.payment_method !== 'pix') return { ok: false, status: 400, error: 'Este pedido não é pago com Pix.' };
  if (row.paid_at) return { ok: true, state: 'paid' };
  const live = (row.statuses ?? []).filter((s) => s && s !== 'cancelado');
  if (live.length === 0) return { ok: false, status: 400, error: 'Este pedido foi cancelado.' };

  const left = row.pix_expires_at ? row.pix_expires_at.getTime() - Date.now() : 0;
  if (row.pix_qr_code && left > 0) {
    return { ok: true, state: 'active', pix: { code: row.pix_qr_code, qr_base64: '', expires_in: Math.floor(left / 1000) } }; // ainda vale: não troca
  }
  if (row.pix_attempts >= MAX_PIX_ATTEMPTS) return { ok: false, status: 429, error: 'Muitos Pix gerados para este pedido. Fale com a gente.' };

  // o Pix anterior pode ter sido pago no último instante: confere no Mercado Pago antes de trocar
  if (row.mp_payment_id) {
    try {
      const old = await getPayment(row.mp_payment_id);
      if (old.status === 'approved') {
        await q(`UPDATE shop_orders SET paid_at = now() WHERE id = $1 AND paid_at IS NULL`, [orderId]);
        await q(`UPDATE orders SET payment = 'pago' WHERE shop_order_id = $1`, [orderId]);
        return { ok: true, state: 'paid' };
      }
      if (old.status === 'pending' || old.status === 'in_process') await cancelPayment(row.mp_payment_id);
    } catch { /* sem resposta: o Pix já venceu; segue para o novo */ }
  }

  try {
    const pay = await issuePix(orderId, row.code, row.total, row.email, row.contact_name, row.pix_attempts + 1);
    return { ok: true, state: 'renewed', pix: { code: pay.code, qr_base64: pay.qrBase64, expires_in: Math.floor((pay.expiresAt.getTime() - Date.now()) / 1000) } };
  } catch (e) {
    console.error('[mercadopago] falha ao gerar novo Pix do pedido', orderId, e);
    return { ok: false, status: 502, error: 'Não conseguimos gerar o Pix agora. Tente de novo em instantes.' };
  }
}
