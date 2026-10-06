import { q, q1, tx } from './db';
import { allocateOrderCode } from './order-code';
import { formatPickupLine, type PickupAddress } from './pickup';
import type { Customer } from './customer-auth';
import { overallStatus, paymentLabel, type ShopOrderInput } from '@shared/shop';
import { anyReasonLabel, paymentState, refundCustomerLabel, type RefundStatus } from '@shared/refund';
import { eligibilityOf } from './refunds';

export type { ShopOrder } from '@shared/types';
import type { ShopOrder } from '@shared/types';

export async function getCustomerOrders(customerId: number): Promise<ShopOrder[]> {
  const rows = await q<any>(
    `SELECT so.id, so.public_code AS code, so.pickup_address, so.created_at, so.scheduled_for, so.time_window, so.fulfillment, so.street, so.number, so.complement, so.district, so.city,
            so.payment_method, so.installments, so.notes, so.total::float8 AS total, so.items, so.paid_at, so.refunded_total::float8 AS refunded_total, so.delivered_at, so.cancelled_at, so.cancelled_by, so.cancel_reason, so.cancel_reason_code, so.cancel_note, so.pix_qr_code, so.pix_qr_base64, so.pix_expires_at, so.manual,
            GREATEST(0, floor(extract(epoch from (so.pix_expires_at - now()))))::int AS pix_left,
            (SELECT array_agg(o.status) FROM orders o WHERE o.shop_order_id = so.id) AS statuses
     FROM shop_orders so WHERE so.customer_id = $1 ORDER BY so.created_at DESC, so.id DESC LIMIT 100`,
    [customerId],
  );
  const ids = rows.map((r) => r.id);
  const refundRows = ids.length ? await q<any>(
    `SELECT r.id, r.shop_order_id, r.kind, r.status, r.reason_code, r.requested_amount::float8 AS requested_amount, r.approved_amount::float8 AS approved_amount,
            r.decision_note, r.decision_code, r.created_at, r.refunded_at, (SELECT count(*)::int FROM refund_photos p WHERE p.refund_id = r.id) AS photos
       FROM refunds r WHERE r.shop_order_id = ANY($1) AND r.status <> 'desistiu' ORDER BY r.created_at DESC, r.id DESC`, [ids]) : [];
  const committed = new Map<number, number>();
  for (const r of refundRows) if (['aprovado', 'processando', 'falhou'].includes(r.status)) committed.set(r.shop_order_id, (committed.get(r.shop_order_id) ?? 0) + (r.approved_amount ?? 0));
  const now = new Date();
  return rows.map(({ statuses, paid_at, pix_qr_code, pix_qr_base64, pix_expires_at, pix_left, delivered_at, cancelled_at, ...r }) => {
    const mine = refundRows.filter((x) => x.shop_order_id === r.id);
    const elig = eligibilityOf({ total: r.total, paid_at, refunded_total: r.refunded_total, committed: committed.get(r.id) ?? 0, statuses: (statuses ?? []).filter(Boolean), delivered_at, liveRequest: mine.some((x) => x.kind === 'solicitado') }, now);
    const status = overallStatus(statuses);
    const expired = pix_expires_at != null && (pix_left ?? 0) <= 0;
    const live = (statuses ?? []).filter(Boolean);
    return {
      ...r,
      // dá para cancelar sozinho: ainda não pago e a confeiteira não começou a produzir (todos os itens "novo")
      can_cancel: live.length > 0 && live.every((s: string) => s === 'novo'), created_at: new Date(r.created_at).toISOString(), status, paid: paid_at != null,
      // Pix vencido: não devolvemos o código (ninguém deve pagar um código que expirou); o app mostra "Gerar novo Pix"
      payment_state: paymentState({ paid: paid_at != null, cancelled: cancelled_at != null || status === 'cancelado', pixExpired: expired, total: r.total, refundedTotal: r.refunded_total }),
      refund: { mode: elig.mode, max_amount: elig.max_amount, photos_required: elig.photos_required, deadline: elig.deadline, why_not: elig.why_not },
      cancel_reason: r.cancel_reason ?? '', cancelled_by: r.cancelled_by ?? null,
      cancel: cancelled_at ? { by: r.cancelled_by ?? 'loja', code: r.cancel_reason_code ?? '', reason: r.cancel_reason ?? '', note: r.cancel_note ?? '', at: new Date(cancelled_at).toISOString() } : null,
      refunds: mine.map((x) => ({
        id: x.id, kind: x.kind, status: (x.status === 'falhou' ? 'processando' : x.status) as RefundStatus, label: refundCustomerLabel(x.status), reason: anyReasonLabel(x.reason_code),
        requested_amount: x.requested_amount, approved_amount: x.approved_amount, decision_note: x.decision_note, decision_reason: x.decision_code ? anyReasonLabel(x.decision_code) : '',
        photos: x.photos, created_at: new Date(x.created_at).toISOString(), refunded_at: x.refunded_at ? new Date(x.refunded_at).toISOString() : null,
      })),
      pix: paid_at == null && pix_qr_code && status !== 'cancelado'
        ? { code: expired ? '' : pix_qr_code, qr_base64: expired ? '' : pix_qr_base64, expires_in: pix_left ?? 0, expired }
        : null,
    };
  });
}

/** Usuário do painel que recebe os pedidos da loja (SHOP_OWNER no .env; padrão "rayka"). */
export async function shopOwnerId(): Promise<number | null> {
  const row = await q1<{ id: number }>('SELECT id FROM users WHERE lower(username) = lower($1)', [process.env.SHOP_OWNER || 'rayka']);
  return row?.id ?? null;
}

const fmtPhone = (d: string) => (d.length === 11 ? `(${d.slice(0, 2)}) ${d.slice(2, 7)}-${d.slice(7)}` : `(${d.slice(0, 2)}) ${d.slice(2, 6)}-${d.slice(6)}`);

/** Grava o pedido da loja e espelha cada item em `orders`, para aparecer na tela Pedidos do painel. */
export async function createShopOrder(customer: Customer, ownerId: number, o: ShopOrderInput): Promise<{ id: number; code: number }> {
  return tx(async (c) => {
    await c.query('SELECT pg_advisory_xact_lock($1)', [ownerId]); // numeração sequencial sem corrida
    // retirada: guarda o endereço de retirada da loja no momento do pedido (trocar o endereço depois não muda pedidos já feitos)
    let pickup = '';
    if (o.fulfillment === 'retirada') {
      const a = (await c.query('SELECT street, number, complement, district, city, instructions FROM pickup_addresses WHERE user_id = $1 AND is_active', [ownerId])).rows[0] as PickupAddress | undefined;
      pickup = a ? formatPickupLine(a) : '';
    }
    const code = await allocateOrderCode(async (n) => (await c.query('SELECT 1 FROM shop_orders WHERE public_code = $1', [n])).rowCount! > 0);
    const { rows } = await c.query(
      `INSERT INTO shop_orders(customer_id, owner_id, contact_name, phone, fulfillment, cep, street, number, complement, district, city,
         scheduled_for, time_window, payment_method, change_for, notes, items, total, public_code, pickup_address)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20) RETURNING id`,
      [customer.id, ownerId, o.contact_name, o.phone, o.fulfillment, o.cep, o.street, o.number, o.complement, o.district, o.city,
        o.scheduled_for, o.time_window, o.payment_method, o.change_for, o.notes, JSON.stringify(o.items), o.total, code, pickup],
    );
    const id = rows[0].id as number;
    const pay = paymentLabel(o.payment_method);
    const where = o.fulfillment === 'entrega'
      ? `Entrega: ${o.street}, ${o.number}${o.complement ? ` (${o.complement})` : ''} - ${o.district}, ${o.city}`
      : pickup ? `Retirada: ${pickup}` : 'Retirada no local';
    const change = o.change_for ? ` (troco p/ R$ ${o.change_for.toFixed(2).replace('.', ',')})` : '';
    for (const [i, it] of o.items.entries()) {
      const notes = [`Pedido online #${code}`, `Tel ${fmtPhone(o.phone)}`, `Pagamento: ${pay}${change}`, i === 0 && o.notes ? `Obs: ${o.notes}` : '']
        .filter(Boolean).join(' · ').slice(0, 300);
      await c.query(
        `INSERT INTO orders(user_id, number, customer, recipe_id, product, qty, unit_price, delivery_date, status, payment, notes, address, shop_order_id)
         VALUES ($1, (SELECT COALESCE(MAX(number),0)+1 FROM orders WHERE user_id=$1), $2,$3,$4,$5,$6,$7,'novo','pendente',$8,$9,$10)`,
        [ownerId, o.contact_name, Number(it.id), it.name, it.qty, it.unit_price, o.scheduled_for, notes, where, id],
      );
    }
    // guarda os dados para preencher o próximo pedido
    await c.query(
      `UPDATE customers SET phone=$2, cep=CASE WHEN $3 = '' THEN cep ELSE $3 END, street=CASE WHEN $3 = '' THEN street ELSE $4 END,
         number=CASE WHEN $3 = '' THEN number ELSE $5 END, complement=CASE WHEN $3 = '' THEN complement ELSE $6 END,
         district=CASE WHEN $3 = '' THEN district ELSE $7 END, city=CASE WHEN $3 = '' THEN city ELSE $8 END WHERE id=$1`,
      [customer.id, o.phone, o.cep, o.street, o.number, o.complement, o.district, o.city],
    );
    return { id, code };
  });
}
