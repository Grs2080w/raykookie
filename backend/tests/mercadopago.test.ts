import { createHmac } from 'node:crypto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { validSignature } from '@/lib/mercadopago';
import { paymentLabel, parseShopOrder, PAYMENT_METHODS } from '@shared/shop';

const sign = (secret: string, id: string, reqId: string, ts: string) =>
  `ts=${ts},v1=${createHmac('sha256', secret).update(`id:${id};request-id:${reqId};ts:${ts};`).digest('hex')}`;

describe('assinatura do webhook', () => {
  it('aceita a assinatura correta e recusa adulterada, sem segredo ou sem cabeçalho', () => {
    const h = sign('segredo', 'abc123', 'req-1', '1700000000');
    expect(validSignature('segredo', 'ABC123', h, 'req-1')).toBe(true); // id alfanumérico é comparado em minúsculas
    expect(validSignature('outro', 'abc123', h, 'req-1')).toBe(false);
    expect(validSignature('segredo', '999', h, 'req-1')).toBe(false);
    expect(validSignature('', 'abc123', h, 'req-1')).toBe(false);
    expect(validSignature('segredo', 'abc123', null, 'req-1')).toBe(false);
  });
});

describe('formas de pagamento', () => {
  it('pix, cartão e dinheiro, com rótulo certo', () => {
    expect(PAYMENT_METHODS.map((p) => p.id)).toEqual(['pix', 'cartao', 'dinheiro']);
    expect(paymentLabel('cartao')).toBe('Cartão');
  });
});

// o webhook só marca "pago" com pagamento aprovado
const tx = vi.fn(), getPayment = vi.fn();
const q1 = vi.fn(), notifyPaid = vi.fn();
vi.mock('@/lib/db', () => ({ tx: (fn: any) => tx(fn), q1: (...a: unknown[]) => q1(...a) }));
const notifyCustomerPaid = vi.fn();
vi.mock('@/lib/push', () => ({ notifyPaid: (...a: unknown[]) => notifyPaid(...a), notifyCustomerPaid: (...a: unknown[]) => notifyCustomerPaid(...a) }));
vi.mock('@/lib/order-events', () => ({ logEvent: vi.fn() }));
const refundLatePayment = vi.fn(), reconcileRefunds = vi.fn();
vi.mock('@/lib/refunds', () => ({ refundLatePayment: (...a: unknown[]) => refundLatePayment(...a), reconcileRefunds: (...a: unknown[]) => reconcileRefunds(...a) }));
vi.mock('@/lib/mercadopago', async (orig) => ({ ...(await orig<any>()), getPayment: (id: string) => getPayment(id) }));

describe('webhook Mercado Pago', () => {
  const secret = 's';
  const call = async (status: string, signature?: string) => {
    const { POST } = await import('@/app/api/webhooks/mercadopago/route');
    getPayment.mockResolvedValue({ id: 7, status, external_reference: '12', transaction_amount: 50 });
    return POST(new Request('http://x/api/webhooks/mercadopago?data.id=7&type=payment', {
      method: 'POST', body: JSON.stringify({ type: 'payment', data: { id: '7' } }),
      headers: { 'x-request-id': 'r', 'x-signature': signature ?? sign(secret, '7', 'r', '1') },
    }));
  };
  beforeEach(() => {
    process.env.MP_WEBHOOK_SECRET = secret; process.env.MP_ACCESS_TOKEN = 't';
    tx.mockReset(); getPayment.mockReset(); q1.mockReset(); notifyPaid.mockReset();
    refundLatePayment.mockReset(); reconcileRefunds.mockReset();
    q1.mockImplementation(async (sql: string) => (String(sql).includes('cancelled_at IS NOT NULL') ? null : { owner_id: 1, customer_id: 7, code: 4821, contact_name: 'Maria', total: 50, n: 2 }));
    tx.mockImplementation(async (fn: any) => fn({ query: vi.fn().mockResolvedValue({ rowCount: 1 }) }));
  });
  it('pagamento aprovado marca o pedido como pago', async () => {
    expect((await call('approved')).status).toBe(200);
    expect(tx).toHaveBeenCalledTimes(1);
    expect(notifyPaid).toHaveBeenCalledWith(1, { id: 12, code: 4821, customer: 'Maria', total: 50, items: 2 });
    expect(notifyCustomerPaid).toHaveBeenCalledWith({ id: 12, code: 4821, customerId: 7, total: 50 }); // a cliente também é avisada
  });
  it('notificação repetida (pedido já pago) não avisa de novo', async () => {
    tx.mockImplementation(async (fn: any) => fn({ query: vi.fn().mockResolvedValue({ rowCount: 0 }) }));
    expect((await call('approved')).status).toBe(200);
    expect(notifyPaid).not.toHaveBeenCalled();
  });
  it('pix só gerado (pending) não marca pago', async () => {
    expect((await call('pending')).status).toBe(200);
    expect(tx).not.toHaveBeenCalled();
    expect(notifyPaid).not.toHaveBeenCalled();
  });
  it('pagamento que chega depois do cancelamento: devolve tudo sozinho e não avisa "novo pagamento"', async () => {
    q1.mockImplementation(async (sql: string) => (String(sql).includes('cancelled_at IS NOT NULL') ? { '?column?': 1 } : null));
    expect((await call('approved')).status).toBe(200);
    expect(refundLatePayment).toHaveBeenCalledWith(12);
    expect(notifyPaid).not.toHaveBeenCalled();
  });
  it('pagamento devolvido (refunded): concilia as devoluções e não marca pago', async () => {
    expect((await call('refunded')).status).toBe(200);
    expect(reconcileRefunds).toHaveBeenCalledWith(12, '7');
    expect(tx).not.toHaveBeenCalled();
  });
  it('assinatura inválida: 401 e nada é consultado', async () => {
    expect((await call('approved', 'ts=1,v1=00')).status).toBe(401);
    expect(getPayment).not.toHaveBeenCalled();
  });
});
