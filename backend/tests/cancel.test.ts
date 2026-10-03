import { beforeEach, describe, expect, it, vi } from 'vitest';

const q = vi.fn(), q1 = vi.fn(), tx = vi.fn();
vi.mock('@/lib/db', () => ({ q: (...a: unknown[]) => q(...a), q1: (...a: unknown[]) => q1(...a), tx: (fn: any) => tx(fn) }));
const getPayment = vi.fn(), cancelPayment = vi.fn(), notifyCancelled = vi.fn();
vi.mock('@/lib/mercadopago', async (orig) => ({ ...(await orig<any>()), getPayment: (...a: unknown[]) => getPayment(...a), cancelPayment: (...a: unknown[]) => cancelPayment(...a) }));
const cancelAndRefund = vi.fn();
vi.mock('@/lib/refunds', () => ({ cancelAndRefund: (...a: unknown[]) => cancelAndRefund(...a) }));
vi.mock('@/lib/order-events', () => ({ logEvent: vi.fn() }));
vi.mock('@/lib/push', async (orig) => ({ ...(await orig<any>()), notifyCancelled: (...a: unknown[]) => notifyCancelled(...a) }));

import { cancelShopOrder } from '@/lib/cancel';
import { cancelMessage } from '@/lib/push';

const row = (over: Record<string, unknown> = {}) => ({ id: 12, code: 4821, owner_id: 1, contact_name: 'Maria', total: 98, payment_method: 'pix', paid_at: null, mp_payment_id: '555', ...over });
/** simula o banco: statuses = situação de cada item do pedido */
const db = (statuses: string[]) => {
  const queries: string[] = [];
  tx.mockImplementation(async (fn: any) => fn({ query: vi.fn(async (sql: string) => { queries.push(sql); return { rows: statuses.map((status) => ({ status })) }; }) }));
  return queries;
};

describe('cancelar pedido (cliente)', () => {
  beforeEach(() => {
    q.mockReset(); q1.mockReset(); tx.mockReset(); cancelAndRefund.mockReset(); getPayment.mockReset(); cancelPayment.mockReset(); notifyCancelled.mockReset();
    q.mockResolvedValue([]);
    getPayment.mockResolvedValue({ id: 555, status: 'pending' });
  });

  it('pedido de outra pessoa ou inexistente: 404', async () => {
    q1.mockResolvedValue(undefined);
    expect(await cancelShopOrder(12, 99, '')).toMatchObject({ ok: false, status: 404 });
    expect(tx).not.toHaveBeenCalled();
  });
  it('pedido já pago: segue para cancelar e devolver o Pix (a regra de "produção não começou" é de lá)', async () => {
    q1.mockResolvedValue(row({ paid_at: new Date() }));
    cancelAndRefund.mockResolvedValue({ ok: true, refundId: 3, refunded: 98 });
    expect(await cancelShopOrder(12, 7, 'Errei', 'errei_pedido')).toEqual({ ok: true, state: 'cancelled', refunded: 98 });
    expect(cancelAndRefund).toHaveBeenCalledWith(12, 7, 'errei_pedido', 'Errei');
    expect(tx).not.toHaveBeenCalled();
  });
  it('pedido pago em produção: o erro vem de cancelAndRefund (409)', async () => {
    q1.mockResolvedValue(row({ paid_at: new Date() }));
    cancelAndRefund.mockResolvedValue({ ok: false, status: 409, error: 'Seu pedido já entrou em produção' });
    expect(await cancelShopOrder(12, 7, '')).toMatchObject({ ok: false, status: 409 });
  });
  it('pedido novo e sem pagamento: cancela os itens, desativa o Pix e avisa a confeiteira', async () => {
    q1.mockResolvedValue(row());
    const queries = db(['novo', 'novo']);
    expect(await cancelShopOrder(12, 7, 'Errei o pedido')).toEqual({ ok: true, state: 'cancelled' });
    expect(cancelPayment).toHaveBeenCalledWith('555');
    expect(queries.some((s) => s.includes("SET status = 'cancelado'"))).toBe(true);
    expect(queries.some((s) => s.includes('cancel_reason'))).toBe(true);
    expect(notifyCancelled).toHaveBeenCalledWith(1, { id: 12, code: 4821, customer: 'Maria', total: 98, reason: 'Errei o pedido' });
  });
  it('pedido em dinheiro: cancela sem falar com o Mercado Pago', async () => {
    q1.mockResolvedValue(row({ payment_method: 'dinheiro', mp_payment_id: null }));
    db(['novo']);
    expect(await cancelShopOrder(12, 7, '')).toEqual({ ok: true, state: 'cancelled' });
    expect(getPayment).not.toHaveBeenCalled();
    expect(cancelPayment).not.toHaveBeenCalled();
  });
  it('a confeiteira já começou a produzir: 409 e nada é cancelado', async () => {
    q1.mockResolvedValue(row());
    const queries = db(['novo', 'producao']);
    expect(await cancelShopOrder(12, 7, '')).toMatchObject({ ok: false, status: 409 });
    expect(queries.some((s) => s.includes("SET status = 'cancelado'"))).toBe(false);
    expect(notifyCancelled).not.toHaveBeenCalled();
  });
  it('o Pix foi pago neste instante: marca como pago e cancela devolvendo', async () => {
    q1.mockResolvedValue(row());
    getPayment.mockResolvedValue({ id: 555, status: 'approved' });
    cancelAndRefund.mockResolvedValue({ ok: true, refundId: 4, refunded: 98 });
    db(['novo']);
    expect(await cancelShopOrder(12, 7, '')).toMatchObject({ ok: true, refunded: 98 });
    expect(q.mock.calls.map((c) => String(c[0])).some((s) => s.includes("payment = 'pago'"))).toBe(true);
    expect(tx).not.toHaveBeenCalled();
  });
  it('já cancelado: devolve ok sem repetir o aviso', async () => {
    q1.mockResolvedValue(row({ payment_method: 'dinheiro', mp_payment_id: null }));
    db(['cancelado']);
    expect(await cancelShopOrder(12, 7, '')).toEqual({ ok: true, state: 'already' });
    expect(notifyCancelled).not.toHaveBeenCalled();
  });
  it('mensagem de push com pedido, cliente, valor e motivo', () => {
    const m = cancelMessage('tok', { id: 12, code: 4821, customer: 'Maria', total: 98, reason: 'Errei o pedido' });
    expect(m.notification).toEqual({ title: 'Pedido #4821 cancelado pela cliente', body: 'Maria cancelou · R$ 98,00 · Errei o pedido' });
    expect(cancelMessage('tok', { id: 12, code: 4821, customer: 'Maria', total: 98, reason: 'Achei caro', refunded: 98 }).notification.body).toBe('Maria cancelou · R$ 98,00 · Achei caro. O Pix de R$ 98,00 foi devolvido automaticamente.');
    expect(m.data).toEqual({ type: 'pedido_cancelado', orderId: '12' });
    expect(m.android.notification.channelId).toBe('pedido_alerta');
  });
});
