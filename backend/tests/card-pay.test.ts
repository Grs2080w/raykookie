import { beforeEach, describe, expect, it, vi } from 'vitest';

const q = vi.fn(), q1 = vi.fn(), tx = vi.fn();
vi.mock('@/lib/db', () => ({ q: (...a: unknown[]) => q(...a), q1: (...a: unknown[]) => q1(...a), tx: (fn: any) => tx(fn) }));
const createCardPayment = vi.fn();
vi.mock('@/lib/mercadopago', async (orig) => ({
  ...(await orig<any>()),
  mpCardConfigured: () => true,
  createCardPayment: (...a: unknown[]) => createCardPayment(...a),
}));
const logEvent = vi.fn();
vi.mock('@/lib/order-events', () => ({ logEvent: (...a: unknown[]) => logEvent(...a) }));
const notifyPaid = vi.fn(), notifyCustomerPaid = vi.fn();
vi.mock('@/lib/push', () => ({ notifyPaid: (...a: unknown[]) => notifyPaid(...a), notifyCustomerPaid: (...a: unknown[]) => notifyCustomerPaid(...a) }));

import { cardFailReason } from '@/lib/mercadopago';
import { MAX_CARD_ATTEMPTS, payWithCard, retryCard } from '@/lib/card-pay';

const base = {
  orderId: 12, code: 4821, total: 98, email: 'maria@example.com', name: 'Maria Souza', cpf: '52998224725',
  cardToken: 'tok_1', paymentMethodId: 'master', installments: 1, ownerId: 1, customerId: 7, items: 2, attempt: 1,
};

describe('motivo da recusa em português', () => {
  it('mapeia os códigos conhecidos e cai num genérico para os demais', () => {
    expect(cardFailReason('cc_rejected_bad_filled_security_code')).toMatch(/código de segurança/);
    expect(cardFailReason('cc_rejected_insufficient_amount')).toMatch(/saldo ou limite/i);
    expect(cardFailReason('algo_novo_que_nao_mapeamos')).toMatch(/tente outro cartão ou pix/i);
  });
});

describe('payWithCard', () => {
  beforeEach(() => {
    q.mockReset(); q1.mockReset(); tx.mockReset(); createCardPayment.mockReset(); logEvent.mockReset(); notifyPaid.mockReset(); notifyCustomerPaid.mockReset();
    tx.mockImplementation(async (fn: any) => fn({ query: vi.fn().mockResolvedValue({ rowCount: 1 }) }));
  });

  it('aprovado: marca pago, loga o evento e avisa a confeiteira e a cliente', async () => {
    createCardPayment.mockResolvedValue({ paymentId: '999', status: 'approved', statusDetail: 'accredited' });
    const r = await payWithCard(base);
    expect(r).toEqual({ ok: true, approved: true });
    expect(logEvent).toHaveBeenCalledWith(12, 'mercadopago', 'pago', expect.objectContaining({ via: 'cartao', installments: 1 }), expect.anything());
    expect(notifyPaid).toHaveBeenCalledWith(1, { id: 12, code: 4821, customer: 'Maria Souza', total: 98, items: 2 });
    expect(notifyCustomerPaid).toHaveBeenCalledWith({ id: 12, code: 4821, customerId: 7, total: 98 });
  });

  it('aprovado mas a guarda não aplicou (já pago por outro meio): não avisa de novo', async () => {
    tx.mockImplementation(async (fn: any) => fn({ query: vi.fn().mockResolvedValue({ rowCount: 0 }) }));
    createCardPayment.mockResolvedValue({ paymentId: '999', status: 'approved', statusDetail: 'accredited' });
    expect(await payWithCard(base)).toEqual({ ok: true, approved: true });
    expect(notifyPaid).not.toHaveBeenCalled();
  });

  it('pendente (in_process): não marca pago, aguarda o webhook', async () => {
    createCardPayment.mockResolvedValue({ paymentId: '999', status: 'in_process', statusDetail: 'pending_contingency' });
    expect(await payWithCard(base)).toEqual({ ok: true, approved: false, pending: true });
    expect(notifyPaid).not.toHaveBeenCalled();
    expect(tx).not.toHaveBeenCalled();
  });

  it('recusado: devolve o motivo em português, não marca pago', async () => {
    createCardPayment.mockResolvedValue({ paymentId: '999', status: 'rejected', statusDetail: 'cc_rejected_insufficient_amount' });
    const r = await payWithCard(base);
    expect(r).toMatchObject({ ok: true, approved: false, pending: false });
    expect((r as any).reason).toMatch(/saldo ou limite/i);
    expect(tx).not.toHaveBeenCalled();
  });

  it('falha ao falar com o Mercado Pago: 502 com mensagem amigável', async () => {
    createCardPayment.mockRejectedValue(new Error('timeout'));
    expect(await payWithCard(base)).toMatchObject({ ok: false, status: 502 });
  });
});

describe('retryCard', () => {
  const row = (over: Record<string, unknown> = {}) => ({
    owner_id: 1, code: 4821, total: 98, contact_name: 'Maria Souza', payment_method: 'cartao', paid_at: null,
    card_attempts: 1, email: 'maria@example.com', n: 2, statuses: ['novo'], ...over,
  });
  beforeEach(() => {
    q.mockReset(); q1.mockReset(); tx.mockReset(); createCardPayment.mockReset(); notifyPaid.mockReset(); notifyCustomerPaid.mockReset();
    tx.mockImplementation(async (fn: any) => fn({ query: vi.fn().mockResolvedValue({ rowCount: 1 }) }));
  });
  const input = { cardToken: 'tok_2', paymentMethodId: 'visa', installments: 1, cpf: '52998224725' };

  it('pedido de outra pessoa ou inexistente: 404', async () => {
    q1.mockResolvedValue(undefined);
    expect(await retryCard(12, 99, input)).toMatchObject({ ok: false, status: 404 });
    expect(createCardPayment).not.toHaveBeenCalled();
  });
  it('pedido que não é cartão: 400', async () => {
    q1.mockResolvedValue(row({ payment_method: 'pix' }));
    expect(await retryCard(12, 7, input)).toMatchObject({ ok: false, status: 400 });
  });
  it('já pago: aprovado sem tentar cobrar de novo', async () => {
    q1.mockResolvedValue(row({ paid_at: new Date() }));
    expect(await retryCard(12, 7, input)).toEqual({ ok: true, approved: true });
    expect(createCardPayment).not.toHaveBeenCalled();
  });
  it('pedido cancelado: 400', async () => {
    q1.mockResolvedValue(row({ statuses: ['cancelado'] }));
    expect(await retryCard(12, 7, input)).toMatchObject({ ok: false, status: 400 });
    expect(createCardPayment).not.toHaveBeenCalled();
  });
  it('limite de tentativas: 429', async () => {
    q1.mockResolvedValue(row({ card_attempts: MAX_CARD_ATTEMPTS }));
    expect(await retryCard(12, 7, input)).toMatchObject({ ok: false, status: 429 });
    expect(createCardPayment).not.toHaveBeenCalled();
  });
  it('tentativa válida: cobra de novo com o número de tentativa certo', async () => {
    q1.mockResolvedValue(row());
    createCardPayment.mockResolvedValue({ paymentId: '999', status: 'approved', statusDetail: 'accredited' });
    expect(await retryCard(12, 7, input)).toEqual({ ok: true, approved: true });
    expect(createCardPayment.mock.calls[0][0]).toMatchObject({ orderId: 12, code: 4821, total: 98, attempt: 2 });
  });
});
