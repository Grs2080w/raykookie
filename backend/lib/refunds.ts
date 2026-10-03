// Devolução de Pix. Regra: o banco decide dentro da transação (com trava), o Mercado Pago é chamado depois, fora dela,
// com uma chave de idempotência fixa por devolução: repetir a chamada nunca devolve duas vezes.
import { q, q1, tx } from './db';
import { createRefund, getRefunds, getPayment, cancelPayment, refundFailReason } from './mercadopago';
import { logEvent } from './order-events';
import { notifyCancelled, notifyCustomerPaid, notifyCustomerRefund, notifyCustomerStoreCancel, notifyOwnerRefund, notifyPaid } from './push';
import {
  DENY_REASONS, FULL_REFUND_CODE, MAX_REFUND_PHOTOS, MAX_REFUND_PHOTO_BYTES, MIN_REFUND_DESCRIPTION, REFUND_REASONS, STORE_CANCEL_REASONS,
  anyReasonLabel, refundEligibility, type Eligibility, type FailReason,
} from '@shared/refund';
import { cleanText, parseLocaleNumber } from '@shared/text';

type Row = Record<string, any>;
export type Fail = { ok: false; status: number; error: string };
const fail = (status: number, error: string): Fail => ({ ok: false, status, error });
const cents = (n: number) => Math.round(n * 100) / 100;

/** Pausa antes de tentar de novo, depois de falhar: 15 min, 1h, 3h, 12h, depois a cada 12h (até 8 tentativas). */
export const RETRY_AFTER_MIN = [15, 60, 180, 720, 720, 720, 720, 720];
export const MAX_ATTEMPTS = RETRY_AFTER_MIN.length;

const IN_FLIGHT = ['aprovado', 'processando', 'falhou'];

/** Situação do pedido para decidir devoluções. `c` = cliente de transação (com FOR UPDATE) ou o pool. */
async function loadOrder(run: (sql: string, p: unknown[]) => Promise<Row[]>, orderId: number, lock: boolean): Promise<Row | null> {
  const so = (await run(
    `SELECT id, owner_id, customer_id, public_code AS code, contact_name, total::float8 AS total, paid_at, paid_mp_id, cancelled_at, items,
            refunded_total::float8 AS refunded_total, delivered_at, payment_method, manual
       FROM shop_orders WHERE id = $1 ${lock ? 'FOR UPDATE' : ''}`, [orderId]))[0];
  if (!so) return null;
  const statuses = (await run(`SELECT status FROM orders WHERE shop_order_id = $1 ${lock ? 'FOR UPDATE' : ''}`, [orderId])).map((r) => r.status as string);
  const asks = (await run(`SELECT status FROM refunds WHERE shop_order_id = $1 AND kind = 'solicitado' AND status <> 'desistiu'`, [orderId])).map((r) => r.status as string);
  const committed = Number((await run(`SELECT COALESCE(sum(approved_amount),0)::float8 AS s FROM refunds WHERE shop_order_id = $1 AND status = ANY($2)`, [orderId, IN_FLIGHT]))[0]?.s ?? 0);
  return { ...so, statuses, liveRequest: asks.length > 0, pendingRequest: asks.includes('solicitado'), committed };
}

const PENDING_MSG = 'Há um pedido de devolução aguardando sua decisão. Decida por ele primeiro (Ver e decidir).';

/**
 * Devolveu (ou vai devolver) tudo o que foi pago: o pedido acabou. Os itens que ainda não foram entregues viram cancelados,
 * para a produção parar e nenhum botão de cancelar ou devolver aparecer de novo. Pedido já entregue continua "entregue".
 * Chamar dentro da transação, depois de gravar a devolução.
 */
async function closeIfFullyRefunded(c: { query: (sql: string, p?: unknown[]) => Promise<{ rows: Row[]; rowCount: number | null }> }, orderId: number): Promise<boolean> {
  const ord = await loadOrder(txRun(c), orderId, true);
  if (!ord?.paid_at || ord.cancelled_at || cents(ord.total - ord.refunded_total - ord.committed) > 0) return false;
  const stopped = await c.query(`UPDATE orders SET status = 'cancelado' WHERE shop_order_id = $1 AND status NOT IN ('cancelado','entregue')`, [orderId]);
  if (!stopped.rowCount) return false;
  await c.query(
    `UPDATE shop_orders SET cancelled_at = now(), cancelled_by = 'sistema', cancel_reason_code = $2, cancel_reason = $3, cancel_note = '' WHERE id = $1`,
    [orderId, FULL_REFUND_CODE, anyReasonLabel(FULL_REFUND_CODE)]);
  await logEvent(orderId, 'sistema', 'cancelado', { code: FULL_REFUND_CODE, paid: true }, c);
  return true;
}
const poolRun = (sql: string, p: unknown[]) => q<Row>(sql, p);
const txRun = (c: { query: (sql: string, p?: unknown[]) => Promise<{ rows: Row[] }> }) => async (sql: string, p: unknown[]) => (await c.query(sql, p)).rows;

export function eligibilityOf(o: Row, now = new Date()): Eligibility {
  const e = refundEligibility({
    paid: o.paid_at != null, paidAt: o.paid_at ? new Date(o.paid_at) : null, total: o.total, refundedTotal: o.refunded_total + (o.committed ?? 0),
    itemStatuses: o.statuses, deliveredAt: o.delivered_at ? new Date(o.delivered_at) : null, liveRequest: o.liveRequest, now,
  });
  return e;
}

// ---------------------------------------------------------------------------------------------------------------- envio ao Mercado Pago

/** Manda a devolução ao Mercado Pago (ou refaz, se falhou). Seguro de repetir. Nunca lança erro. */
export async function sendRefund(refundId: number): Promise<'concluido' | 'processando' | 'falhou' | 'ignorado'> {
  const claimed = await q1<Row>(
    `UPDATE refunds SET status = 'processando', attempts = attempts + 1, next_try_at = NULL
      WHERE id = $1 AND status IN ('aprovado','processando','falhou') AND approved_amount > 0
      RETURNING id, shop_order_id, approved_amount::float8 AS amount, attempts, kind`, [refundId]);
  if (!claimed) return 'ignorado';
  const o = await q1<Row>(`SELECT owner_id, customer_id, public_code AS code, contact_name, total::float8 AS total, paid_mp_id, refunded_total::float8 AS refunded_total FROM shop_orders WHERE id = $1`, [claimed.shop_order_id]);
  if (!o?.paid_mp_id) return markFailed(refundId, claimed, o, 'erro', 'Pedido sem identificação do pagamento');
  try {
    // devolução total do que resta = sem valor (o Mercado Pago devolve tudo); parcial = valor exato
    const wholeRest = cents(o.total - o.refunded_total) <= cents(claimed.amount);
    const mr = await createRefund(o.paid_mp_id, wholeRest && o.refunded_total === 0 ? null : claimed.amount, `rayka-refund-${refundId}`);
    const done = mr.status === 'approved';
    const first = await tx(async (c) => {
      const upd = await c.query(
        `UPDATE refunds SET status = $2, mp_refund_id = $3, mp_status = $4, fail_reason = NULL, next_try_at = NULL, refunded_at = CASE WHEN $2 = 'concluido' THEN now() ELSE refunded_at END
          WHERE id = $1 AND status <> 'concluido' RETURNING id`, [refundId, done ? 'concluido' : 'processando', String(mr.id), mr.status]);
      if (done && upd.rowCount) {
        await c.query('UPDATE shop_orders SET refunded_total = LEAST(total, refunded_total + $2) WHERE id = $1', [claimed.shop_order_id, claimed.amount]);
        await logEvent(claimed.shop_order_id, 'mercadopago', 'devolucao_concluida', { refund_id: refundId, amount: claimed.amount, mp_refund_id: String(mr.id) }, c);
      } else if (upd.rowCount) {
        await logEvent(claimed.shop_order_id, 'mercadopago', 'devolucao_enviada', { refund_id: refundId, amount: claimed.amount, mp_status: mr.status }, c);
      }
      return !!upd.rowCount;
    });
    if (done && first) {
      void notifyCustomerRefund(o.customer_id, { id: claimed.shop_order_id, code: o.code }, { kind: 'concluida', amount: claimed.amount });
      if (claimed.attempts > 1) void notifyOwnerRefund(o.owner_id, { kind: 'concluida', refundId, code: o.code, amount: claimed.amount }); // só avisa a confeiteira quando antes tinha falhado
    }
    return done ? 'concluido' : 'processando';
  } catch (e) {
    return markFailed(refundId, claimed, o, refundFailReason(e), String((e as Error)?.message ?? e).slice(0, 300));
  }
}

async function markFailed(refundId: number, r: Row, o: Row | null, why: FailReason, detail: string): Promise<'falhou'> {
  const wait = RETRY_AFTER_MIN[r.attempts - 1];
  await q(
    `UPDATE refunds SET status = 'falhou', fail_reason = $2, next_try_at = CASE WHEN $3::int IS NULL THEN NULL ELSE now() + make_interval(mins => $3::int) END WHERE id = $1`,
    [refundId, why, wait ?? null]);
  await logEvent(r.shop_order_id, 'sistema', 'devolucao_falhou', { refund_id: refundId, why, detail, attempt: r.attempts });
  if (o) void notifyOwnerRefund(o.owner_id, { kind: why === 'saldo' ? 'saldo' : 'falha', refundId, code: o.code, amount: r.amount, stalled: wait == null });
  return 'falhou';
}

/** Chamado pelo job: refaz as que falharam (quando chegou a hora) e as que ficaram "processando" sem resposta. */
export async function retryDue(): Promise<number> {
  const rows = await q<{ id: number }>(
    `SELECT id FROM refunds WHERE (status = 'falhou' AND next_try_at IS NOT NULL AND next_try_at <= now())
        OR (status = 'processando' AND mp_refund_id IS NULL AND refunded_at IS NULL AND created_at < now() - interval '10 minutes' AND attempts < $1)
      ORDER BY id LIMIT 20`, [MAX_ATTEMPTS]);
  for (const r of rows) await sendRefund(r.id);
  return rows.length;
}

// ---------------------------------------------------------------------------------------------------------------- cancelar e devolver

export type CancelRefundResult = { ok: true; refundId: number | null; refunded: number } | Fail;

type CancelOpts = { actor: 'cliente' | 'loja' | 'sistema'; code: string; note: string; ownerId?: number; customerId?: number; onlyNew: boolean };

async function cancelCore(orderId: number, o: CancelOpts): Promise<CancelRefundResult> {
  const label = anyReasonLabel(o.code);
  const out = await tx(async (c) => {
    const ord = await loadOrder(txRun(c), orderId, true);
    if (!ord || (o.customerId != null && ord.customer_id !== o.customerId) || (o.ownerId != null && ord.owner_id !== o.ownerId)) return fail(404, 'Pedido não encontrado.');
    if (ord.cancelled_at || (ord.statuses.length > 0 && ord.statuses.every((s: string) => s === 'cancelado'))) return fail(409, 'Este pedido já está cancelado.');
    if (ord.pendingRequest) return fail(409, o.actor === 'cliente' ? 'Você já pediu a devolução deste pedido. Aguarde a resposta da loja.' : PENDING_MSG);
    // pedido manual já 100% devolvido por "Devolver valor" segue aberto: cancelar só encerra (amount = 0, sem linha em refunds)
    if (ord.paid_at && !ord.manual && cents(ord.total - ord.refunded_total - ord.committed) <= 0) return fail(409, 'Todo o valor deste pedido já foi devolvido.');
    if (o.onlyNew && !ord.statuses.every((s: string) => s === 'novo' || s === 'cancelado')) return fail(409, 'Seu pedido já entrou em produção e não pode mais ser cancelado por aqui. Peça a devolução pelo pedido.');
    if (!o.onlyNew && ord.statuses.length > 0 && ord.statuses.every((s: string) => s === 'entregue' || s === 'cancelado')) return fail(409, 'Este pedido já foi entregue. Use "Devolver valor".');
    await c.query(`UPDATE orders SET status = 'cancelado' WHERE shop_order_id = $1 AND status <> 'cancelado' AND status <> 'entregue'`, [orderId]);
    await c.query(`UPDATE shop_orders SET cancelled_at = now(), cancel_reason = $2, cancel_reason_code = $3, cancelled_by = $4, cancel_note = $5 WHERE id = $1`, [orderId, label.slice(0, 200), o.code, o.actor, o.note.slice(0, 300)]);
    await logEvent(orderId, o.actor, 'cancelado', { code: o.code, note: o.note, paid: ord.paid_at != null }, c);
    let refundId: number | null = null, amount = 0;
    if (ord.paid_at) {
      amount = cents(ord.total - ord.refunded_total - ord.committed);
      if (amount > 0) {
        const r = (await c.query(
          `INSERT INTO refunds(shop_order_id, owner_id, customer_id, kind, status, reason_code, description, requested_amount, approved_amount, decided_at)
           VALUES ($1,$2,$3,$4,'processando',$5,$6,$7,$7,now()) RETURNING id`,
          [orderId, ord.owner_id, ord.customer_id, o.actor === 'cliente' ? 'automatico' : o.actor === 'loja' ? 'loja' : 'sistema', o.code, o.note, amount])).rows[0];
        refundId = r.id;
        await logEvent(orderId, o.actor, 'devolucao_aprovada', { refund_id: refundId, amount, automatic: true }, c);
      }
    }
    return { ok: true as const, refundId, refunded: amount, ord };
  });
  if (!out.ok) return out;
  // fora da transação: desativa o Pix pendente (pedido não pago) e manda a devolução
  if (!out.ord.paid_at && out.ord.payment_method === 'pix') {
    const ids = (await q1<{ ids: string[] }>('SELECT mp_payment_ids AS ids FROM shop_orders WHERE id = $1', [orderId]))?.ids ?? [];
    for (const id of ids) { try { const p = await getPayment(id); if (p.status === 'pending' || p.status === 'in_process') await cancelPayment(id); } catch { /* vence sozinho */ } }
  }
  // avisa a outra ponta antes de devolver: quem lê primeiro vê "cancelado", depois "Pix devolvido"
  const ord = out.ord;
  if (o.actor === 'cliente') void notifyCancelled(ord.owner_id, { id: orderId, code: ord.code, customer: ord.contact_name, total: ord.total, reason: [label, o.note].filter(Boolean).join(': '), refunded: out.refunded });
  if (o.actor === 'loja') void notifyCustomerStoreCancel({ id: orderId, code: ord.code, customerId: ord.customer_id, total: ord.total }, label, out.refunded);
  if (out.refundId) await sendRefund(out.refundId);
  return { ok: true, refundId: out.refundId, refunded: out.refunded };
}

/** O cliente cancela um pedido já pago e ainda não iniciado: devolve o total na hora. */
export const cancelAndRefund = (orderId: number, customerId: number, code: string, note: string) =>
  cancelCore(orderId, { actor: 'cliente', code, note, customerId, onlyNew: true });

/** A confeiteira cancela o pedido (pago ou não). Se pago, devolve tudo o que resta. */
export async function ownerCancel(orderId: number, ownerId: number, code: unknown, note: unknown): Promise<CancelRefundResult> {
  if (!STORE_CANCEL_REASONS.some((r) => r.id === code)) return fail(400, 'Escolha o motivo do cancelamento.');
  const n = cleanText(note).slice(0, 300);
  if (code === 'outro' && n.length < 3) return fail(400, 'Conte o motivo do cancelamento.');
  return cancelCore(orderId, { actor: 'loja', code: String(code), note: n, ownerId, onlyNew: false });
}

/** Pagamento que chegou depois de o pedido ter sido cancelado: devolve tudo, sem ninguém precisar agir. */
export const refundLatePayment = (orderId: number) => cancelLate(orderId);
async function cancelLate(orderId: number): Promise<number | null> {
  const id = await tx(async (c) => {
    const ord = await loadOrder(txRun(c), orderId, true);
    if (!ord || !ord.paid_at || !ord.cancelled_at) return null;
    const left = cents(ord.total - ord.refunded_total - ord.committed);
    if (left <= 0) return null;
    const r = (await c.query(
      `INSERT INTO refunds(shop_order_id, owner_id, customer_id, kind, status, reason_code, description, requested_amount, approved_amount, decided_at)
       VALUES ($1,$2,$3,'sistema','processando','pago_apos_cancelamento','Pagamento recebido depois do cancelamento',$4,$4,now()) RETURNING id`,
      [orderId, ord.owner_id, ord.customer_id, left])).rows[0];
    await logEvent(orderId, 'sistema', 'devolucao_aprovada', { refund_id: r.id, amount: left, why: 'pago_apos_cancelamento' }, c);
    return r.id as number;
  });
  if (id) { await sendRefund(id); const o = await q1<Row>('SELECT owner_id, public_code AS code FROM shop_orders WHERE id = $1', [orderId]); if (o) void notifyOwnerRefund(o.owner_id, { kind: 'tardio', refundId: id, code: o.code }); }
  return id;
}

/** A confeiteira devolve um valor sem cancelar o pedido (ex.: um item faltou). */
export async function ownerRefund(orderId: number, ownerId: number, amount: unknown, note: unknown): Promise<CancelRefundResult> {
  const value = cents(parseLocaleNumber(String(amount ?? ''))); // pt-BR: "1.000" é mil, "12,5" é 12,50 (como o app digita)
  const n = cleanText(note).slice(0, 300);
  if (!(value > 0)) return fail(400, 'Informe o valor da devolução.');
  if (n.length < 3) return fail(400, 'Conte o motivo da devolução.');
  const out = await tx(async (c) => {
    const ord = await loadOrder(txRun(c), orderId, true);
    if (!ord || ord.owner_id !== ownerId) return fail(404, 'Pedido não encontrado.');
    if (!ord.paid_at) return fail(409, 'Este pedido ainda não foi pago.');
    if (ord.pendingRequest) return fail(409, PENDING_MSG);
    const left = cents(ord.total - ord.refunded_total - ord.committed);
    if (left <= 0) return fail(409, 'Todo o valor deste pedido já foi devolvido.');
    if (value > left) return fail(400, `O valor passa do que ainda pode ser devolvido (R$ ${left.toFixed(2).replace('.', ',')}).`);
    const r = (await c.query(
      `INSERT INTO refunds(shop_order_id, owner_id, customer_id, kind, status, reason_code, description, requested_amount, approved_amount, decision_note, decided_at)
       VALUES ($1,$2,$3,'loja','processando','outro',$4,$5,$5,$4,now()) RETURNING id`, [orderId, ord.owner_id, ord.customer_id, n, value])).rows[0];
    await logEvent(orderId, 'loja', 'devolucao_aprovada', { refund_id: r.id, amount: value, note: n }, c);
    if (!ord.manual) await closeIfFullyRefunded(c, orderId); // pedido manual (link de pagamento) pode ter sido pago em dobro ou ser só sinal: devolver o Pix não o encerra
    return { ok: true as const, refundId: r.id as number, refunded: value };
  });
  if (out.ok) await sendRefund(out.refundId!);
  return out;
}

// ---------------------------------------------------------------------------------------------------------------- solicitação do cliente

export type PhotoIn = { type: string; data: Buffer };
export type RequestInput = { reason_code: unknown; description: unknown; items: unknown; photos: PhotoIn[] };

export async function requestRefund(orderId: number, customerId: number, b: RequestInput): Promise<{ ok: true; refundId: number } | Fail> {
  const reason = REFUND_REASONS.find((r) => r.id === b.reason_code);
  if (!reason) return fail(400, 'Escolha o motivo da devolução.');
  const description = cleanText(b.description).replace(/\s+/g, ' ').slice(0, 1000);
  if (description.length < MIN_REFUND_DESCRIPTION) return fail(400, `Conte o que aconteceu (pelo menos ${MIN_REFUND_DESCRIPTION} caracteres).`);
  if (b.photos.length > MAX_REFUND_PHOTOS) return fail(400, `Envie no máximo ${MAX_REFUND_PHOTOS} fotos.`);
  for (const p of b.photos) if (p.data.length > MAX_REFUND_PHOTO_BYTES) return fail(413, 'Uma das fotos é grande demais (máximo de 2 MB).');

  const out = await tx(async (c) => {
    const ord = await loadOrder(txRun(c), orderId, true);
    if (!ord || ord.customer_id !== customerId) return fail(404, 'Pedido não encontrado.');
    const e = eligibilityOf(ord);
    if (e.mode !== 'solicitacao') return fail(409, e.why_not || 'Este pedido não aceita pedido de devolução. Fale com a gente.');
    if (e.photos_required && reason.photos === 'sim' && b.photos.length < 1) return fail(400, 'Envie pelo menos uma foto para mostrar o problema.');
    // itens marcados: precisam existir no pedido, com quantidade que não passe do pedido
    const ordered = new Map<string, { name: string; qty: number; unit_price: number }>((ord.items as any[]).map((it) => [String(it.id), it]));
    const picked: { id: string; name: string; qty: number }[] = [];
    let requested = 0;
    for (const raw of Array.isArray(b.items) ? (b.items as any[]) : []) {
      const it = ordered.get(String(raw?.id));
      const qty = Math.floor(Number(raw?.qty));
      if (!it || !(qty >= 1) || qty > it.qty) return fail(400, 'Confira os itens marcados: a quantidade passa do que foi pedido.');
      picked.push({ id: String(raw.id), name: it.name, qty });
      requested += it.unit_price * qty;
    }
    if (!picked.length) return fail(400, 'Marque pelo menos um item com problema.');
    requested = Math.min(cents(requested), e.max_amount);
    const r = (await c.query(
      `INSERT INTO refunds(shop_order_id, owner_id, customer_id, kind, status, reason_code, description, items, requested_amount, priority)
       VALUES ($1,$2,$3,'solicitado','solicitado',$4,$5,$6,$7,$8) RETURNING id`,
      [orderId, ord.owner_id, customerId, reason.id, description, JSON.stringify(picked), requested, !!reason.priority])).rows[0];
    for (const [i, p] of b.photos.entries()) {
      await c.query('INSERT INTO refund_photos(refund_id, idx, content_type, data, size) VALUES ($1,$2,$3,$4,$5)', [r.id, i + 1, p.type, p.data, p.data.length]);
    }
    await logEvent(orderId, 'cliente', 'devolucao_solicitada', { refund_id: r.id, reason: reason.id, requested, photos: b.photos.length }, c);
    return { ok: true as const, refundId: r.id as number, owner: ord.owner_id as number, code: ord.code as number, name: ord.contact_name as string, requested, priority: !!reason.priority };
  });
  if (!out.ok) return out;
  void notifyOwnerRefund(out.owner, { kind: 'solicitada', refundId: out.refundId, code: out.code, customer: out.name, amount: out.requested, priority: out.priority, reason: reason.label, photos: b.photos.length });
  return { ok: true, refundId: out.refundId };
}

/** O cliente desiste da solicitação, enquanto ela ainda não foi analisada. */
export async function withdrawRefund(orderId: number, customerId: number): Promise<{ ok: true } | Fail> {
  const r = await q1<Row>(
    `UPDATE refunds r SET status = 'desistiu', decided_at = now() FROM shop_orders so
      WHERE so.id = r.shop_order_id AND r.shop_order_id = $1 AND r.customer_id = $2 AND r.kind = 'solicitado' AND r.status = 'solicitado'
      RETURNING r.id, so.owner_id, so.public_code AS code, so.contact_name`, [orderId, customerId]);
  if (!r) return fail(409, 'Não há solicitação em análise para cancelar.');
  await logEvent(orderId, 'cliente', 'devolucao_desistiu', { refund_id: r.id });
  void notifyOwnerRefund(r.owner_id, { kind: 'desistiu', refundId: r.id, code: r.code, customer: r.contact_name });
  return { ok: true };
}

// ---------------------------------------------------------------------------------------------------------------- decisão da confeiteira

export type Decision = { approve: boolean; amount?: unknown; code?: unknown; note?: unknown };
export async function decideRefund(refundId: number, ownerId: number, d: Decision): Promise<{ ok: true; status: string } | Fail> {
  const note = cleanText(d.note).slice(0, 500);
  const out = await tx(async (c) => {
    const r = (await c.query(`SELECT * FROM refunds WHERE id = $1 AND owner_id = $2 FOR UPDATE`, [refundId, ownerId])).rows[0] as Row | undefined;
    if (!r) return fail(404, 'Solicitação não encontrada.');
    if (r.status !== 'solicitado') return fail(409, 'Esta solicitação já foi decidida.');
    if (d.approve) {
      const ord = await loadOrder(txRun(c), r.shop_order_id, true);
      const left = cents(ord!.total - ord!.refunded_total - ord!.committed);
      const value = d.amount == null || d.amount === '' ? Number(r.requested_amount) : cents(parseLocaleNumber(String(d.amount)));
      if (!(value > 0)) return fail(400, 'Informe o valor a devolver.');
      if (value > left) return fail(400, `O valor passa do que ainda pode ser devolvido (R$ ${left.toFixed(2).replace('.', ',')}).`);
      const partial = value < Number(r.requested_amount);
      if (partial && note.length < 3) return fail(400, 'Para devolver menos que o pedido, explique o motivo ao cliente.');
      await c.query(`UPDATE refunds SET status = 'aprovado', approved_amount = $2, decision_note = $3, decided_at = now() WHERE id = $1`, [refundId, value, note]);
      await logEvent(r.shop_order_id, 'loja', 'devolucao_aprovada', { refund_id: refundId, amount: value, partial, note }, c);
      await closeIfFullyRefunded(c, r.shop_order_id);
      return { ok: true as const, status: 'aprovado', push: { customer: r.customer_id as number, order: r.shop_order_id as number, code: ord!.code as number, kind: 'aprovada' as const, amount: value, requested: Number(r.requested_amount) } };
    }
    if (!DENY_REASONS.some((x) => x.id === d.code)) return fail(400, 'Escolha o motivo da negativa.');
    if (d.code === 'outro' && note.length < 3) return fail(400, 'Explique o motivo ao cliente.');
    await c.query(`UPDATE refunds SET status = 'negado', decision_code = $2, decision_note = $3, decided_at = now() WHERE id = $1`, [refundId, d.code, note]);
    await logEvent(r.shop_order_id, 'loja', 'devolucao_negada', { refund_id: refundId, code: d.code, note }, c);
    const code = (await c.query('SELECT public_code FROM shop_orders WHERE id = $1', [r.shop_order_id])).rows[0]?.public_code as number;
    return { ok: true as const, status: 'negado', push: { customer: r.customer_id as number, order: r.shop_order_id as number, code, kind: 'negada' as const, reason: anyReasonLabel(String(d.code)) } };
  });
  if (!out.ok) return out;
  const p = out.push;
  void notifyCustomerRefund(p.customer, { id: p.order, code: p.code }, p.kind === 'aprovada' ? { kind: 'aprovada', amount: p.amount, requested: p.requested } : { kind: 'negada', reason: p.reason });
  if (out.status === 'aprovado') await sendRefund(refundId);
  return { ok: true, status: out.status };
}

/** Tentar de novo uma devolução que falhou (botão da confeiteira). */
export async function retryRefund(refundId: number, ownerId: number): Promise<{ ok: true; status: string } | Fail> {
  const r = await q1<Row>(`SELECT id, status FROM refunds WHERE id = $1 AND owner_id = $2`, [refundId, ownerId]);
  if (!r) return fail(404, 'Devolução não encontrada.');
  if (r.status !== 'falhou' && r.status !== 'processando') return fail(409, 'Esta devolução não precisa ser refeita.');
  return { ok: true, status: await sendRefund(refundId) };
}

// ---------------------------------------------------------------------------------------------------------------- conciliação com o Mercado Pago

/** Confere as devoluções de um pagamento no Mercado Pago: fecha as nossas e registra as feitas direto no painel do Mercado Pago. */
export async function reconcileRefunds(orderId: number, paymentId: string): Promise<void> {
  let list;
  try { list = await getRefunds(paymentId); } catch { return; }
  for (const mr of list) {
    if (mr.status !== 'approved') continue;
    const done = await tx(async (c) => {
      const known = (await c.query('SELECT id, status FROM refunds WHERE mp_refund_id = $1', [String(mr.id)])).rows[0];
      const ord = (await c.query('SELECT owner_id, customer_id, public_code AS code, total::float8 AS total FROM shop_orders WHERE id = $1 FOR UPDATE', [orderId])).rows[0];
      if (!ord) return null;
      if (known) {
        if (known.status === 'concluido') return null;
        await c.query(`UPDATE refunds SET status = 'concluido', mp_status = 'approved', refunded_at = now(), fail_reason = NULL, next_try_at = NULL WHERE id = $1`, [known.id]);
      } else {
        await c.query(
          `INSERT INTO refunds(shop_order_id, owner_id, customer_id, kind, status, reason_code, description, requested_amount, approved_amount, decision_note, decided_at, mp_refund_id, mp_status, refunded_at)
           VALUES ($1,$2,$3,'loja','concluido','outro','Devolução feita direto no Mercado Pago',$4,$4,'Feita no Mercado Pago',now(),$5,'approved',now())`,
          [orderId, ord.owner_id, ord.customer_id, mr.amount, String(mr.id)]);
      }
      await c.query('UPDATE shop_orders SET refunded_total = LEAST(total, refunded_total + $2) WHERE id = $1', [orderId, mr.amount]);
      await logEvent(orderId, 'mercadopago', 'devolucao_concluida', { amount: mr.amount, mp_refund_id: String(mr.id), reconciled: true }, c);
      return { customer: ord.customer_id as number, code: ord.code as number };
    });
    if (done) void notifyCustomerRefund(done.customer, { id: orderId, code: done.code }, { kind: 'concluida', amount: mr.amount });
  }
}

/** Conferência periódica (a cada 6 h): Pix pendente que o webhook pode ter perdido e devoluções ainda "processando". */
export async function reconcilePending(): Promise<number> {
  let n = 0;
  const open = await q<{ id: number; mp_payment_ids: string[] }>(
    `SELECT id, mp_payment_ids FROM shop_orders WHERE paid_at IS NULL AND created_at > now() - interval '2 days' AND mp_payment_ids IS NOT NULL AND cardinality(mp_payment_ids) > 0`);
  for (const o of open) {
    for (const pid of o.mp_payment_ids) {
      try {
        const p = await getPayment(pid);
        if (p.status !== 'approved') continue;
        const set = await q1<Row>(`UPDATE shop_orders SET paid_at = now(), paid_mp_id = $2 WHERE id = $1 AND paid_at IS NULL AND $3::numeric >= total RETURNING id, cancelled_at`, [o.id, pid, p.transaction_amount]);
        if (set) {
          await q(`UPDATE orders SET payment = 'pago' WHERE shop_order_id = $1`, [o.id]);
          await logEvent(o.id, 'sistema', 'pago', { payment: pid, via: 'conciliacao' });
          if (set.cancelled_at) await refundLatePayment(o.id);
          else {
            const so = await q1<Row>('SELECT owner_id, customer_id, public_code AS code, contact_name, total::float8 AS total, jsonb_array_length(items)::int AS n FROM shop_orders WHERE id = $1', [o.id]);
            if (so) {
              void notifyPaid(so.owner_id, { id: o.id, code: so.code, customer: so.contact_name, total: so.total, items: so.n });
              void notifyCustomerPaid({ id: o.id, code: so.code, customerId: so.customer_id, total: so.total });
            }
          }
          n++;
        }
      } catch { /* sem resposta: tenta na próxima */ }
    }
  }
  const proc = await q<{ shop_order_id: number; paid_mp_id: string }>(
    `SELECT DISTINCT r.shop_order_id, so.paid_mp_id FROM refunds r JOIN shop_orders so ON so.id = r.shop_order_id WHERE r.status = 'processando' AND r.mp_refund_id IS NOT NULL AND so.paid_mp_id IS NOT NULL`);
  for (const r of proc) { await reconcileRefunds(r.shop_order_id, r.paid_mp_id); n++; }
  return n;
}
