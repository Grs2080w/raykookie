import { randomBytes } from 'node:crypto';
import type { PoolClient } from 'pg';
import { q, q1, tx } from './db';
import { cancelPayment, cardFailReason, createCardPayment, createPixPayment, getPayment, mpCardConfigured, mpConfigured, pixExpiresMinutes, type MpPayment } from './mercadopago';
import { MAX_PIX_ATTEMPTS } from './pix';
import { MAX_CARD_ATTEMPTS } from './card-pay';
import { siteUrl } from './site';
import { allocateOrderCode } from './order-code';
import { logEvent } from './order-events';
import { syncDelivered } from './order-sync';
import { notifyPaid } from './push';
import { reconcileRefunds, refundLatePayment } from './refunds';
import { getCustomerOrders } from './shop-queries';
import type { ShopOrder } from '@shared/types';

/** Prefixo do `external_reference` dos Pix de link de pagamento (o do pedido da loja é só o número do pedido). */
export const LINK_REF = 'link:';

/** E-mail do pagador no Mercado Pago (pedido registrado à mão não tem conta de cliente). */
export const payerEmail = () =>
  process.env.PAY_LINK_PAYER_EMAIL || `pagamentos@${new URL(siteUrl()).hostname.replace(/^www\./, '')}`;

/** 32 bytes em base64url = 43 caracteres. Qualquer outra coisa nem chega ao banco. */
export const isPayToken = (t: string) => /^[A-Za-z0-9_-]{43}$/.test(t);
const newToken = () => randomBytes(32).toString('base64url');

export type EnsureResult = { ok: true; url: string; amount: number } | { ok: false; status: number; error: string };

/** Cria (ou reaproveita) o link de pagamento de um pedido registrado à mão. `amount` nulo cobra o total da linha. */
export async function ensurePayLink(userId: number, orderId: number, amount: number | null): Promise<EnsureResult> {
  const o = await q1<{ status: string; payment: string; shop_order_id: number | null; total: number }>(
    `SELECT status, payment, shop_order_id, (qty * unit_price)::float8 AS total FROM orders WHERE id = $1 AND user_id = $2`, [orderId, userId]);
  if (!o) return { ok: false, status: 404, error: 'Pedido não encontrado.' };
  if (o.shop_order_id != null) return { ok: false, status: 400, error: 'Pedido do app: o cliente paga pelo próprio app.' };
  if (o.status === 'cancelado') return { ok: false, status: 409, error: 'Este pedido foi cancelado.' };
  if (o.payment === 'pago') return { ok: false, status: 409, error: 'Este pedido já está pago.' };
  const total = Math.round(o.total * 100) / 100;
  const value = Math.round((amount ?? total) * 100) / 100;
  if (!Number.isFinite(value) || value < 0.01 || value > total) {
    return { ok: false, status: 400, error: `O valor precisa ficar entre R$ 0,01 e R$ ${total.toFixed(2).replace('.', ',')}.` };
  }
  if (!mpConfigured()) return { ok: false, status: 503, error: 'O Pix não está disponível agora. Tente de novo mais tarde.' };

  const prev = await q1<{ amount: number; mp_payment_id: string | null; paid_at: Date | null }>(
    `SELECT amount::float8 AS amount, mp_payment_id, paid_at FROM order_pay_links WHERE order_id = $1`, [orderId]);
  if (prev?.paid_at) return { ok: false, status: 409, error: 'Este pedido já foi pago pelo link.' };
  // valor mudou: o Pix que existia cobra o valor antigo, então some junto (a página gera outro ao pagar)
  const row = await q1<{ token: string }>(
    `INSERT INTO order_pay_links(order_id, token, amount) VALUES ($1, $2, $3)
     ON CONFLICT (order_id) DO UPDATE SET
       amount = EXCLUDED.amount,
       pix_qr_code    = CASE WHEN order_pay_links.amount = EXCLUDED.amount THEN order_pay_links.pix_qr_code ELSE '' END,
       pix_qr_base64  = CASE WHEN order_pay_links.amount = EXCLUDED.amount THEN order_pay_links.pix_qr_base64 ELSE '' END,
       pix_expires_at = CASE WHEN order_pay_links.amount = EXCLUDED.amount THEN order_pay_links.pix_expires_at ELSE NULL END
     WHERE order_pay_links.paid_at IS NULL
     RETURNING token`,
    [orderId, newToken(), value]);
  if (!row) return { ok: false, status: 409, error: 'Este pedido já foi pago pelo link.' };
  if (prev && prev.amount !== value && prev.mp_payment_id) await cancelPayment(prev.mp_payment_id);
  return { ok: true, url: siteUrl(`/pagar/${row.token}`), amount: value };
}

export type PayLinkView = {
  state: 'aberto' | 'pago' | 'cancelado';
  amount: number;
  order: { number: number; customer: string; product: string; qty: number; unit_price: number; delivery_date: string | null };
  pix: { code: string; qr_base64: string; expires_in: number; expired: boolean } | null;
  shop_order: ShopOrder | null;
};

/** O que a página pública mostra. Só lê: abrir o link (inclusive a prévia do WhatsApp) nunca gera Pix. */
export async function payLinkView(token: string): Promise<PayLinkView | null> {
  if (!isPayToken(token)) return null;
  const r = await q1<{
    amount: number; paid_at: Date | null; pix_qr_code: string; pix_qr_base64: string; pix_expires_at: Date | null;
    number: number; customer: string; product: string; qty: number; unit_price: number; status: string; payment: string; delivery_date: string | null;
  }>(
    `SELECT l.amount::float8 AS amount, l.paid_at, l.pix_qr_code, l.pix_qr_base64, l.pix_expires_at,
            o.number, o.customer, o.product, o.qty::float8 AS qty, o.unit_price::float8 AS unit_price, o.status, o.payment, o.delivery_date
       FROM order_pay_links l JOIN orders o ON o.id = l.order_id WHERE l.token = $1`, [token]);
  if (!r) return null;
  const state = r.paid_at || r.payment === 'pago' ? 'pago' : r.status === 'cancelado' ? 'cancelado' : 'aberto';
  const left = r.pix_expires_at ? Math.max(0, Math.floor((r.pix_expires_at.getTime() - Date.now()) / 1000)) : 0;
  const g = r.paid_at ? await linkGuest(token) : null;
  const shop_order = g ? ((await getCustomerOrders(g.customerId)).find((o) => o.id === g.shopOrderId) ?? null) : null;
  return {
    state, amount: r.amount,
    order: { number: r.number, customer: r.customer, product: r.product, qty: r.qty, unit_price: r.unit_price, delivery_date: r.delivery_date },
    pix: r.pix_qr_code ? { code: r.pix_qr_code, qr_base64: r.pix_qr_base64, expires_in: left, expired: left <= 0 } : null,
    shop_order,
  };
}

/** Convidado dono do pedido de um link PAGO (promove na hora um link antigo). null: token ruim, inexistente ou ainda não pago. */
export async function linkGuest(token: string): Promise<{ customerId: number; shopOrderId: number } | null> {
  if (!isPayToken(token)) return null;
  const read = () => q1<{ id: number; paid_at: Date | null; shop_order_id: number | null; customer_id: number | null }>(
    `SELECT l.id, l.paid_at, l.shop_order_id, so.customer_id FROM order_pay_links l LEFT JOIN shop_orders so ON so.id = l.shop_order_id WHERE l.token = $1`, [token]);
  let l = await read();
  if (!l?.paid_at) return null;
  if (!l.shop_order_id) { await promoteLink(l.id); l = await read(); }
  return l?.shop_order_id && l.customer_id ? { customerId: l.customer_id, shopOrderId: l.shop_order_id } : null;
}

export type PayLinkPixResult =
  | { ok: true; state: 'paid' }
  | { ok: true; state: 'active' | 'renewed'; pix: { code: string; qr_base64: string; expires_in: number; expired: false } }
  | { ok: false; status: number; error: string };

export type Promoted = { shopOrderId: number; ownerId: number; code: number; name: string; amount: number; late: boolean; double: boolean; delivered: boolean; legacy: boolean };

const qtyLabel = (n: number) => String(n).replace('.', ',');

/** Dentro da transação: o pedido registrado à mão pago pelo link vira pedido do app (cliente convidado + shop_orders ligado).
 *  Idempotente: link já promovido não faz nada. `paidMpId` = o pagamento aprovado. */
async function promoteInTx(c: PoolClient, linkId: number, paidMpId: string, legacy = false): Promise<Promoted | null> {
  const l = (await c.query(
    `SELECT l.amount::float8 AS amount, l.mp_payment_ids, l.shop_order_id, o.id AS order_id, o.user_id, o.customer, o.product, o.qty::float8 AS qty,
            o.status, o.payment, o.order_date, o.delivery_date
       FROM order_pay_links l JOIN orders o ON o.id = l.order_id WHERE l.id = $1 FOR UPDATE OF l`, [linkId])).rows[0];
  if (!l || l.shop_order_id != null) return null;
  const cancelled = l.status === 'cancelado';
  const guest = (await c.query(`INSERT INTO customers(email, name, guest) VALUES ($1, $2, true) RETURNING id`, [`link-${linkId}@convidado.invalid`, l.customer])).rows[0].id as number;
  const code = await allocateOrderCode(async (n) => (await c.query('SELECT 1 FROM shop_orders WHERE public_code = $1', [n])).rowCount! > 0);
  const items = [{ id: `manual-${l.order_id}`, name: `${qtyLabel(l.qty)} × ${l.product}`, qty: 1, unit_price: l.amount }];
  const so = (await c.query(
    `INSERT INTO shop_orders(customer_id, owner_id, contact_name, phone, fulfillment, scheduled_for, time_window, payment_method, items, total, public_code,
                             paid_at, paid_mp_id, mp_payment_ids, manual, cancelled_at, cancelled_by, cancel_reason_code, cancel_reason)
     VALUES ($1,$2,$3,'','retirada',COALESCE($4::date,$5::date),'tarde','pix',$6,$7,$8, now(),$9,$10,true,$11,$12,$13,$14) RETURNING id`,
    [guest, l.user_id, l.customer, l.delivery_date, l.order_date, JSON.stringify(items), l.amount, code, paidMpId, l.mp_payment_ids,
      cancelled ? new Date() : null, cancelled ? 'loja' : null, cancelled ? 'outro' : null, cancelled ? 'Pedido cancelado pela loja' : ''])).rows[0].id as number;
  await c.query(`UPDATE orders SET shop_order_id = $1, payment = CASE WHEN status = 'cancelado' THEN payment ELSE 'pago' END WHERE id = $2`, [so, l.order_id]);
  await c.query(`UPDATE order_pay_links SET shop_order_id = $1 WHERE id = $2`, [so, linkId]);
  await logEvent(so, 'mercadopago', 'pago', { payment: paidMpId, amount: l.amount, via: 'link' }, c);
  return { shopOrderId: so, ownerId: l.user_id, code, name: l.customer, amount: l.amount, late: cancelled && !legacy, double: !cancelled && !legacy && l.payment === 'pago', delivered: l.status === 'entregue', legacy };
}

/** Depois de confirmada a transação da promoção: prazo de devolução, aviso à confeiteira e, se o pedido já estava cancelado, devolve o Pix sozinho. */
async function afterPromotion(p: Promoted): Promise<void> {
  const step = async (what: string, f: () => unknown) => { try { await f(); } catch (e) { console.error(`[pagamento por link] falha ao ${what}`, p.shopOrderId, e); } };
  if (p.late) await step('devolver pagamento de pedido cancelado', () => refundLatePayment(p.shopOrderId)); // dinheiro primeiro
  if (p.delivered) await step('iniciar o prazo de devolução', () => p.legacy
    ? q(`UPDATE shop_orders SET delivered_at = (SELECT paid_at FROM order_pay_links WHERE shop_order_id = $1) WHERE id = $1 AND delivered_at IS NULL`, [p.shopOrderId]) // link antigo: a janela conta do pagamento antigo
    : syncDelivered(p.shopOrderId));
  if (!p.legacy) await step('avisar a confeiteira', () => notifyPaid(p.ownerId, { id: p.shopOrderId, code: p.code, customer: p.double ? `${p.name} (pagamento em dobro: use "Devolver valor" para devolver o Pix)` : p.name, total: p.amount, items: 1 }));
}

/** Promove um link já pago que ainda não virou pedido do app (links pagos antes desta funcionalidade). `true` se promoveu agora. */
export async function promoteLink(linkId: number): Promise<boolean> {
  const p = await tx(async (c) => {
    await c.query(`SELECT o.id FROM orders o JOIN order_pay_links l ON l.order_id = o.id WHERE l.id = $1 FOR UPDATE OF o`, [linkId]); // mesma ordem de trava do markLinkPaid
    const l = (await c.query(`SELECT paid_mp_id, shop_order_id FROM order_pay_links WHERE id = $1 AND paid_at IS NOT NULL FOR UPDATE`, [linkId])).rows[0];
    return l && l.shop_order_id == null && l.paid_mp_id ? promoteInTx(c, linkId, l.paid_mp_id, true) : null;
  });
  if (p) await afterPromotion(p);
  return !!p;
}

/** Marca o link e o pedido como pagos e promove o pedido a pedido do app. Só vale para pagamento gerado para este link e que cobre o valor cobrado.
 *  Devolve o id do pedido marcado, ou null se não se aplica (já pago, pagamento de outro link, valor menor). */
export async function markLinkPaid(linkId: number, mpPaymentId: string, paidAmount: number): Promise<number | null> {
  const done = await tx(async (c) => {
    // trava o pedido: saber se ele já estava cancelado ou pago (o dinheiro entrou de qualquer jeito)
    const before = (await c.query(
      `SELECT o.id, o.status, o.payment FROM order_pay_links l JOIN orders o ON o.id = l.order_id WHERE l.id = $1 FOR UPDATE OF o`, [linkId])).rows[0] as
      { id: number; status: string; payment: string } | undefined;
    const { rows } = await c.query(
      `UPDATE order_pay_links SET paid_at = now(), paid_mp_id = $2
        WHERE id = $1 AND $2 = ANY(mp_payment_ids) AND paid_at IS NULL AND $3::numeric >= amount RETURNING order_id`,
      [linkId, mpPaymentId, paidAmount]);
    if (!rows[0]) return null;
    if (before && (before.status === 'cancelado' || before.payment === 'pago')) {
      console.warn('[pagamento por link] pagamento recebido para pedido cancelado ou já pago — devolver pelo painel do Mercado Pago, se for o caso',
        { linkId, orderId: before.id, status: before.status, payment: before.payment, mpPaymentId, paidAmount });
    }
    const promo = await promoteInTx(c, linkId, mpPaymentId);
    return { orderId: rows[0].order_id as number, promo };
  });
  if (!done) return null;
  if (done.promo) await afterPromotion(done.promo);
  return done.orderId;
}

/** Pagamento confirmado pelo webhook (external_reference "link:<id>"). Estorno e outros estados são ignorados: a devolução, se houver, é no painel do Mercado Pago. */
export async function handleLinkPayment(pay: MpPayment, linkId: number): Promise<{ ok: true; status: string; paid?: boolean }> {
  // estorno/chargeback do Pix de um link já promovido a pedido do app: concilia a devolução (link não promovido segue ignorado)
  if (Number.isInteger(linkId) && (['refunded', 'charged_back'].includes(pay.status) || ((pay as { transaction_amount_refunded?: number }).transaction_amount_refunded ?? 0) > 0)) {
    const l = await q1<{ shop_order_id: number | null; paid_mp_id: string | null }>('SELECT shop_order_id, paid_mp_id FROM order_pay_links WHERE id = $1', [linkId]);
    if (l?.shop_order_id && l.paid_mp_id === String(pay.id)) await reconcileRefunds(l.shop_order_id, String(pay.id));
    return { ok: true, status: pay.status };
  }
  if (!Number.isInteger(linkId) || pay.status !== 'approved') return { ok: true, status: pay.status };
  const orderId = await markLinkPaid(linkId, String(pay.id), pay.transaction_amount);
  if (orderId == null) console.warn('[pagamento por link] pagamento aprovado que não foi aplicado (já pago, de outro link ou valor menor que o cobrado)', { linkId, payment: pay.id, amount: pay.transaction_amount });
  return { ok: true, status: pay.status, paid: orderId != null };
}

/** "Pagar com Pix" da página pública: gera o Pix (primeiro ou novo). Ainda válido, devolve o mesmo; vencido, confere o antigo no Mercado Pago antes de trocar. */
export async function payLinkPix(token: string): Promise<PayLinkPixResult> {
  if (!isPayToken(token)) return { ok: false, status: 404, error: 'Link não encontrado.' };
  if (!mpConfigured()) return { ok: false, status: 503, error: 'O Pix não está disponível agora. Tente de novo mais tarde.' };
  const l = await q1<{
    id: number; amount: number; paid_at: Date | null; mp_payment_id: string | null; pix_qr_code: string; pix_qr_base64: string; pix_expires_at: Date | null;
    pix_attempts: number; number: number; customer: string; status: string; payment: string; total: number;
  }>(
    `SELECT l.id, l.amount::float8 AS amount, l.paid_at, l.mp_payment_id, l.pix_qr_code, l.pix_qr_base64, l.pix_expires_at, l.pix_attempts,
            o.number, o.customer, o.status, o.payment, (o.qty * o.unit_price)::float8 AS total
       FROM order_pay_links l JOIN orders o ON o.id = l.order_id WHERE l.token = $1`, [token]);
  if (!l) return { ok: false, status: 404, error: 'Link não encontrado.' };
  if (l.paid_at || l.payment === 'pago') return { ok: true, state: 'paid' };
  if (l.status === 'cancelado') return { ok: false, status: 409, error: 'Este pedido foi cancelado.' };
  // a confeiteira baixou o valor do pedido depois de compartilhar: não cobra mais que o total atual
  if (l.amount > Math.round(l.total * 100) / 100) return { ok: false, status: 409, error: 'O valor deste pedido mudou. Peça um novo link para a loja.' };

  const left = l.pix_expires_at ? l.pix_expires_at.getTime() - Date.now() : 0;
  if (l.pix_qr_code && left > 0) {
    return { ok: true, state: 'active', pix: { code: l.pix_qr_code, qr_base64: l.pix_qr_base64, expires_in: Math.floor(left / 1000), expired: false } };
  }
  if (l.pix_attempts >= MAX_PIX_ATTEMPTS) return { ok: false, status: 429, error: 'Muitos Pix gerados para este pedido. Fale com a gente.' };

  // o Pix anterior pode ter sido pago no último instante: confere no Mercado Pago antes de trocar
  if (l.mp_payment_id) {
    let old: MpPayment | null = null;
    try {
      old = await getPayment(l.mp_payment_id);
      if (old.status === 'pending' || old.status === 'in_process') await cancelPayment(l.mp_payment_id);
    } catch (e) {
      console.warn('[pagamento por link] não consegui conferir o Pix anterior no Mercado Pago', l.id, e);
    }
    if (old?.status === 'approved' && (await markLinkPaid(l.id, l.mp_payment_id, old.transaction_amount)) != null) return { ok: true, state: 'paid' };
    if (old?.status === 'approved') {
      // o webhook pode ter pago o link entre a leitura acima e a consulta: nunca gerar outro Pix para pedido já pago
      const now = await q1<{ paid_at: Date | null; payment: string }>(
        `SELECT l.paid_at, o.payment FROM order_pay_links l JOIN orders o ON o.id = l.order_id WHERE l.id = $1`, [l.id]);
      if (now?.paid_at || now?.payment === 'pago') return { ok: true, state: 'paid' };
      console.warn('[pagamento por link] Pix anterior aprovado que não foi aplicado (valor menor que o cobrado)', { linkId: l.id, payment: l.mp_payment_id, amount: old.transaction_amount });
    }
  }

  const attempt = l.pix_attempts + 1;
  const expiresAt = new Date(Date.now() + pixExpiresMinutes() * 60_000);
  try {
    const pay = await createPixPayment({
      orderId: l.id, reference: `${LINK_REF}${l.id}`, code: l.number, total: l.amount, email: payerEmail(), name: l.customer, attempt, expiresAt,
    });
    await q(
      `UPDATE order_pay_links SET mp_payment_id = $2, pix_qr_code = $3, pix_qr_base64 = $4, pix_expires_at = $5, pix_attempts = $6,
         mp_payment_ids = array_append(mp_payment_ids, $2) WHERE id = $1`,
      [l.id, pay.paymentId, pay.code, pay.qrBase64, expiresAt, attempt]);
    return { ok: true, state: 'renewed', pix: { code: pay.code, qr_base64: pay.qrBase64, expires_in: Math.floor((expiresAt.getTime() - Date.now()) / 1000), expired: false } };
  } catch (e) {
    console.error('[mercadopago] falha ao gerar Pix do link de pagamento', l.id, e);
    return { ok: false, status: 502, error: 'Não conseguimos gerar o Pix agora. Tente de novo em instantes.' };
  }
}

export type PayLinkCardResult =
  | { ok: true; approved: true }
  | { ok: true; approved: false; pending: true }
  | { ok: true; approved: false; pending: false; reason: string }
  | { ok: false; status: number; error: string };

/** "Pagar com cartão" da página pública do link: aprova ou recusa na hora (ao contrário do Pix). Aprovado,
 *  promove a pedido do app pelo mesmo `markLinkPaid` que o Pix do link já usa. */
export async function payLinkCard(token: string, cardToken: string, paymentMethodId: string, installments: number, cpf: string): Promise<PayLinkCardResult> {
  if (!isPayToken(token)) return { ok: false, status: 404, error: 'Link não encontrado.' };
  if (!mpCardConfigured()) return { ok: false, status: 503, error: 'O pagamento com cartão não está disponível agora.' };
  const l = await q1<{ id: number; amount: number; paid_at: Date | null; card_attempts: number; number: number; customer: string; status: string; payment: string; total: number }>(
    `SELECT l.id, l.amount::float8 AS amount, l.paid_at, l.card_attempts,
            o.number, o.customer, o.status, o.payment, (o.qty * o.unit_price)::float8 AS total
       FROM order_pay_links l JOIN orders o ON o.id = l.order_id WHERE l.token = $1`, [token]);
  if (!l) return { ok: false, status: 404, error: 'Link não encontrado.' };
  if (l.paid_at || l.payment === 'pago') return { ok: true, approved: true };
  if (l.status === 'cancelado') return { ok: false, status: 409, error: 'Este pedido foi cancelado.' };
  if (l.amount > Math.round(l.total * 100) / 100) return { ok: false, status: 409, error: 'O valor deste pedido mudou. Peça um novo link para a loja.' };
  if (l.card_attempts >= MAX_CARD_ATTEMPTS) return { ok: false, status: 429, error: 'Muitas tentativas de cartão para este pedido. Peça para pagar com Pix.' };

  let pay;
  try {
    pay = await createCardPayment({
      orderId: l.id, code: l.number, total: l.amount, email: payerEmail(), name: l.customer, cpf,
      cardToken, paymentMethodId, installments, attempt: l.card_attempts + 1, reference: `${LINK_REF}${l.id}`,
    });
  } catch (e) {
    console.error('[pagamento por link] falha ao cobrar o cartão', l.id, e);
    return { ok: false, status: 502, error: 'Não conseguimos falar com o Mercado Pago agora. Tente de novo ou pague com Pix.' };
  }
  if (pay.status === 'approved') {
    // precisa estar em mp_payment_ids antes: é a guarda que o markLinkPaid confere
    await q(`UPDATE order_pay_links SET mp_payment_id = $2, mp_payment_ids = array_append(mp_payment_ids, $2), installments = $3 WHERE id = $1`, [l.id, pay.paymentId, installments]);
    const orderId = await markLinkPaid(l.id, pay.paymentId, l.amount);
    if (orderId == null) console.warn('[pagamento por link] cartão aprovado que não foi aplicado (já pago por outro meio)', { linkId: l.id, payment: pay.paymentId });
    return { ok: true, approved: true }; // o dinheiro foi cobrado de qualquer forma; markLinkPaid só não repete se já tiver sido aplicado antes
  }
  if (pay.status === 'pending' || pay.status === 'in_process') {
    await q(`UPDATE order_pay_links SET mp_payment_ids = array_append(mp_payment_ids, $2), card_attempts = card_attempts + 1 WHERE id = $1`, [l.id, pay.paymentId]);
    return { ok: true, approved: false, pending: true };
  }
  await q(`UPDATE order_pay_links SET card_attempts = card_attempts + 1 WHERE id = $1`, [l.id]);
  return { ok: true, approved: false, pending: false, reason: cardFailReason(pay.statusDetail) };
}

/** Rede de segurança do webhook: links com Pix gerado e ainda não pagos (últimos 2 dias) são conferidos no Mercado Pago. Devolve quantos foram aplicados. */
export async function reconcileLinks(): Promise<number> {
  let n = 0;
  const open = await q<{ id: number; mp_payment_ids: string[] }>(
    `SELECT id, mp_payment_ids FROM order_pay_links WHERE paid_at IS NULL AND cardinality(mp_payment_ids) > 0 AND created_at > now() - interval '2 days'`);
  for (const l of open) {
    for (const pid of l.mp_payment_ids) {
      try {
        const p = await getPayment(pid);
        if (p.status !== 'approved') continue;
        if ((await markLinkPaid(l.id, pid, p.transaction_amount)) != null) { n++; break; }
      } catch (e) { console.error('[pagamento por link] falha ao conciliar', { link: l.id, payment: pid }, e); } // tenta na próxima
    }
  }
  const legacy = await q<{ id: number }>(`SELECT id FROM order_pay_links WHERE paid_at IS NOT NULL AND shop_order_id IS NULL`);
  for (const l of legacy) { try { if (await promoteLink(l.id)) n++; } catch (e) { console.error('[pagamento por link] falha ao promover link antigo', l.id, e); } }
  // recuperação: pedido promovido já cancelado cujo estorno automático não chegou a ser criado (falha depois do commit)
  const orphans = await q<{ id: number }>(
    `SELECT so.id FROM shop_orders so JOIN order_pay_links l ON l.shop_order_id = so.id AND l.paid_at = so.paid_at /* pagamento novo: mesma transação; link antigo promovido depois fica de fora */
      WHERE so.manual AND so.paid_at IS NOT NULL AND so.cancelled_at IS NOT NULL AND so.cancelled_by = 'loja' AND so.refunded_total = 0
        AND NOT EXISTS (SELECT 1 FROM refunds r WHERE r.shop_order_id = so.id)`);
  for (const o of orphans) { try { if (await refundLatePayment(o.id)) n++; } catch (e) { console.error('[pagamento por link] falha ao devolver pagamento de pedido cancelado', o.id, e); } }
  return n;
}
