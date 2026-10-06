import { createHmac, timingSafeEqual } from 'node:crypto';
import { siteUrl } from './site';

const API = process.env.MP_API_BASE || 'https://api.mercadopago.com'; // MP_API_BASE só nos testes de ponta a ponta (servidor de mentira)

export type MpPayment = {
  id: number; status: string; external_reference: string | null; transaction_amount: number;
  point_of_interaction?: { transaction_data?: { qr_code?: string; qr_code_base64?: string } };
};

export const mpConfigured = () => !!process.env.MP_ACCESS_TOKEN;
/** Chave pública (não secreta) usada pelo site e pelo app para tokenizar o cartão direto com o Mercado Pago. */
export const mpPublicKey = () => process.env.MP_PUBLIC_KEY || null;
export const mpCardConfigured = () => mpConfigured() && !!mpPublicKey();

export class MpError extends Error {
  constructor(public status: number, public body: string) { super(`Mercado Pago ${status}: ${body.slice(0, 300)}`); }
}

async function mp<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(`${API}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${process.env.MP_ACCESS_TOKEN}`, 'Content-Type': 'application/json', ...init.headers },
    signal: AbortSignal.timeout(10_000),
  });
  if (!res.ok) throw new MpError(res.status, await res.text());
  return res.json() as Promise<T>;
}

/** Validade do Pix em minutos (PIX_EXPIRES_MINUTES no .env). O Mercado Pago aceita de 30 minutos a 30 dias; padrão nosso: 60. */
export function pixExpiresMinutes(): number {
  const n = Number(process.env.PIX_EXPIRES_MINUTES);
  return Number.isFinite(n) && n > 0 ? Math.min(Math.max(Math.round(n), 31), 30 * 24 * 60) : 60;
}

/** Data no formato do Mercado Pago (ISO 8601 com fuso), sempre em horário de Brasília (-03:00). */
export function mpDate(d: Date): string {
  return new Date(d.getTime() - 3 * 3600_000).toISOString().replace('Z', '-03:00');
}

/** Cria a cobrança Pix do pedido. `external_reference` é o id do pedido da loja (é por ele que o webhook acha o pedido);
 *  `reference` (ex.: "link:12") troca isso para outras cobranças, como o link de pagamento de pedido registrado à mão. */
export async function createPixPayment(o: { orderId: number; code: number; total: number; email: string; name: string; attempt?: number; expiresAt: Date; reference?: string }) {
  const idem = o.reference ? o.reference.replace(':', '-') : `order-${o.orderId}`;
  const p = await mp<MpPayment>('/v1/payments', {
    method: 'POST',
    headers: { 'X-Idempotency-Key': `rayka-${idem}-${o.attempt ?? 1}` }, // um Pix novo (tentativa seguinte) precisa de outra chave
    body: JSON.stringify({
      transaction_amount: o.total,
      description: `Pedido #${o.code} - Raykookie`,
      payment_method_id: 'pix',
      payer: { email: o.email, first_name: o.name.split(' ')[0] },
      external_reference: o.reference ?? String(o.orderId),
      date_of_expiration: mpDate(o.expiresAt),
      notification_url: siteUrl('/api/webhooks/mercadopago'),
    }),
  });
  const td = p.point_of_interaction?.transaction_data;
  if (!td?.qr_code) throw new Error('Mercado Pago não devolveu o Pix copia e cola');
  return { paymentId: String(p.id), code: td.qr_code, qrBase64: td.qr_code_base64 ?? '', expiresAt: o.expiresAt };
}

export const getPayment = (id: string) => mp<MpPayment>(`/v1/payments/${encodeURIComponent(id)}`);

export type MpCardResult = { paymentId: string; status: string; statusDetail: string };

/** Cria a cobrança no cartão (aprova ou recusa na mesma chamada, ao contrário do Pix). Dados do cartão nunca
 *  passam por aqui como número bruto: `cardToken` já veio pronto do Mercado Pago (tokenizado no site/app do cliente). */
export async function createCardPayment(o: {
  orderId: number; code: number; total: number; email: string; name: string; cpf: string;
  cardToken: string; paymentMethodId: string; installments: number; attempt: number; reference?: string;
}): Promise<MpCardResult> {
  const idem = o.reference ? o.reference.replace(':', '-') : `order-${o.orderId}`;
  const p = await mp<{ id: number; status: string; status_detail: string }>('/v1/payments', {
    method: 'POST',
    headers: { 'X-Idempotency-Key': `rayka-card-${idem}-${o.attempt}` },
    body: JSON.stringify({
      transaction_amount: o.total,
      token: o.cardToken,
      description: `Pedido #${o.code} - Raykookie`,
      installments: o.installments,
      payment_method_id: o.paymentMethodId,
      payer: { email: o.email, first_name: o.name.split(' ')[0], identification: { type: 'CPF', number: o.cpf } },
      external_reference: o.reference ?? String(o.orderId),
      notification_url: siteUrl('/api/webhooks/mercadopago'),
    }),
  });
  return { paymentId: String(p.id), status: p.status, statusDetail: p.status_detail };
}

const CARD_FAIL_REASONS: Record<string, string> = {
  cc_rejected_bad_filled_security_code: 'O código de segurança (CVV) está errado.',
  cc_rejected_bad_filled_date: 'A validade do cartão está errada.',
  cc_rejected_bad_filled_card_number: 'O número do cartão está errado.',
  cc_rejected_bad_filled_other: 'Confira os dados do cartão e tente de novo.',
  cc_rejected_insufficient_amount: 'Saldo ou limite insuficiente.',
  cc_rejected_call_for_authorize: 'O banco pediu para autorizar essa compra antes. Fale com o banco ou tente outro cartão.',
  cc_rejected_card_disabled: 'Esse cartão está desabilitado para compras online. Fale com o banco ou tente outro cartão.',
  cc_rejected_duplicated_payment: 'Já existe um pagamento igual feito há pouco com esse cartão.',
  cc_rejected_high_risk: 'O pagamento foi recusado por segurança. Tente outro cartão ou Pix.',
  cc_rejected_max_attempts: 'Muitas tentativas com esse cartão. Tente outro cartão ou Pix.',
};
/** Mensagem em português para o motivo da recusa; cai num genérico se o código não for um dos mapeados. */
export const cardFailReason = (statusDetail: string): string =>
  CARD_FAIL_REASONS[statusDetail] ?? 'Não conseguimos aprovar esse cartão. Tente outro cartão ou Pix.';

/** Confere o cabeçalho `x-signature` (ts=...,v1=...) das notificações: HMAC-SHA256 de `id:<data.id>;request-id:<x-request-id>;ts:<ts>;`. */
export function validSignature(secret: string, dataId: string, xSignature: string | null, xRequestId: string | null): boolean {
  if (!secret || !xSignature) return false;
  const parts = Object.fromEntries(xSignature.split(',').map((kv) => { const i = kv.indexOf('='); return [kv.slice(0, i).trim(), kv.slice(i + 1).trim()]; }));
  if (!parts.ts || !parts.v1) return false;
  const id = /^[a-z0-9]+$/i.test(dataId) ? dataId.toLowerCase() : dataId;
  const manifest = `id:${id};${xRequestId ? `request-id:${xRequestId};` : ''}ts:${parts.ts};`;
  const expected = createHmac('sha256', secret).update(manifest).digest('hex');
  const a = Buffer.from(expected), b = Buffer.from(parts.v1);
  return a.length === b.length && timingSafeEqual(a, b);
}

/** Cancela um pagamento ainda pendente (usado ao trocar um Pix vencido por um novo). Falha de rede não é erro: o Pix já venceu de qualquer forma. */
export async function cancelPayment(id: string): Promise<void> {
  try { await mp(`/v1/payments/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify({ status: 'cancelled' }) }); } catch { /* já expirado ou cancelado */ }
}

export type MpRefund = { id: number; payment_id?: number; amount: number; status: string; date_created?: string };

/** Devolve o Pix: `amount` nulo devolve o total. A chave de idempotência impede devolver duas vezes se a chamada for repetida. */
export function createRefund(paymentId: string, amount: number | null, idemKey: string) {
  return mp<MpRefund>(`/v1/payments/${encodeURIComponent(paymentId)}/refunds`, {
    method: 'POST',
    headers: { 'X-Idempotency-Key': idemKey },
    body: JSON.stringify(amount == null ? {} : { amount }),
  });
}

export const getRefunds = (paymentId: string) => mp<MpRefund[]>(`/v1/payments/${encodeURIComponent(paymentId)}/refunds`);

/** Por que a devolução falhou: sem saldo na conta do Mercado Pago, prazo do Pix vencido ou outro erro (tenta de novo depois). */
export function refundFailReason(e: unknown): 'saldo' | 'prazo' | 'erro' {
  const status = e instanceof MpError ? e.status : 0;
  const text = e instanceof MpError ? e.body : String((e as Error)?.message ?? '');
  if (status === 428 || /insufficient|amount_exceeds|not_enough/i.test(text)) return 'saldo';
  if (/refund_period|period_exceeded|expired/i.test(text) || (status === 409 && /period/i.test(text))) return 'prazo';
  return 'erro';
}
