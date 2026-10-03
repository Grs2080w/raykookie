import { beforeEach, describe, expect, it, vi } from 'vitest';

const q = vi.fn(), q1 = vi.fn();
vi.mock('@/lib/db', () => ({ q: (...a: unknown[]) => q(...a), q1: (...a: unknown[]) => q1(...a), tx: vi.fn() }));
const createPixPayment = vi.fn(), getPayment = vi.fn(), cancelPayment = vi.fn();
vi.mock('@/lib/mercadopago', async (orig) => ({
  ...(await orig<any>()),
  mpConfigured: () => true,
  createPixPayment: (...a: unknown[]) => createPixPayment(...a),
  getPayment: (...a: unknown[]) => getPayment(...a),
  cancelPayment: (...a: unknown[]) => cancelPayment(...a),
}));

import { mpDate, pixExpiresMinutes } from '@/lib/mercadopago';
import { MAX_PIX_ATTEMPTS, regeneratePix } from '@/lib/pix';

const row = (over: Record<string, unknown> = {}) => ({
  id: 12, code: 4821, total: 98, contact_name: 'Maria', payment_method: 'pix', paid_at: null, mp_payment_id: '555', pix_qr_code: 'CODIGO-ANTIGO',
  pix_expires_at: new Date(Date.now() - 60_000), pix_attempts: 1, statuses: ['novo'], email: 'maria@example.com', ...over,
});

describe('validade do Pix', () => {
  it('formata a data para o Mercado Pago em horário de Brasília', () => {
    expect(mpDate(new Date('2026-10-02T16:45:12.000Z'))).toBe('2026-10-02T13:45:12.000-03:00');
  });
  it('respeita os limites do Mercado Pago (mínimo 30 minutos, máximo 30 dias) e usa 60 por padrão', () => {
    const old = process.env.PIX_EXPIRES_MINUTES;
    delete process.env.PIX_EXPIRES_MINUTES; expect(pixExpiresMinutes()).toBe(60);
    process.env.PIX_EXPIRES_MINUTES = '5'; expect(pixExpiresMinutes()).toBe(31);
    process.env.PIX_EXPIRES_MINUTES = '120'; expect(pixExpiresMinutes()).toBe(120);
    process.env.PIX_EXPIRES_MINUTES = '999999'; expect(pixExpiresMinutes()).toBe(43200);
    process.env.PIX_EXPIRES_MINUTES = 'abc'; expect(pixExpiresMinutes()).toBe(60);
    if (old === undefined) delete process.env.PIX_EXPIRES_MINUTES; else process.env.PIX_EXPIRES_MINUTES = old;
  });
});

describe('gerar novo Pix', () => {
  beforeEach(() => {
    q.mockReset(); q1.mockReset(); createPixPayment.mockReset(); getPayment.mockReset(); cancelPayment.mockReset();
    q.mockResolvedValue([]);
    getPayment.mockResolvedValue({ id: 555, status: 'pending' });
    createPixPayment.mockImplementation(async (o: any) => ({ paymentId: '777', code: 'CODIGO-NOVO', qrBase64: 'QR', expiresAt: o.expiresAt }));
  });

  it('pedido de outra pessoa ou inexistente: 404', async () => {
    q1.mockResolvedValue(undefined);
    expect(await regeneratePix(12, 99)).toMatchObject({ ok: false, status: 404 });
    expect(createPixPayment).not.toHaveBeenCalled();
  });
  it('pedido que não é Pix: 400', async () => {
    q1.mockResolvedValue(row({ payment_method: 'dinheiro' }));
    expect(await regeneratePix(12, 7)).toMatchObject({ ok: false, status: 400 });
  });
  it('pedido cancelado: 400, sem gerar Pix', async () => {
    q1.mockResolvedValue(row({ statuses: ['cancelado'] }));
    expect(await regeneratePix(12, 7)).toMatchObject({ ok: false, status: 400 });
    expect(createPixPayment).not.toHaveBeenCalled();
  });
  it('pedido já pago: devolve "paid" e não gera outro Pix', async () => {
    q1.mockResolvedValue(row({ paid_at: new Date() }));
    expect(await regeneratePix(12, 7)).toEqual({ ok: true, state: 'paid' });
    expect(createPixPayment).not.toHaveBeenCalled();
  });
  it('Pix ainda válido: não troca o código', async () => {
    q1.mockResolvedValue(row({ pix_expires_at: new Date(Date.now() + 20 * 60_000) }));
    const r: any = await regeneratePix(12, 7);
    expect(r.state).toBe('active');
    expect(r.pix.expires_in).toBeGreaterThan(19 * 60);
    expect(createPixPayment).not.toHaveBeenCalled();
  });
  it('Pix vencido: gera um novo, com outra chave (tentativa 2) e nova validade', async () => {
    q1.mockResolvedValue(row());
    const r: any = await regeneratePix(12, 7);
    expect(r.state).toBe('renewed');
    expect(r.pix.code).toBe('CODIGO-NOVO');
    expect(r.pix.expires_in).toBeGreaterThan(59 * 60 - 5);
    const arg = createPixPayment.mock.calls[0][0];
    expect(arg).toMatchObject({ orderId: 12, code: 4821, total: 98, email: 'maria@example.com', attempt: 2 });
    expect(arg.expiresAt.getTime()).toBeGreaterThan(Date.now() + 59 * 60_000);
    expect(cancelPayment).toHaveBeenCalledWith('555'); // o antigo, ainda pendente no Mercado Pago, é cancelado
    const update = q.mock.calls.map((c) => String(c[0])).find((s) => s.includes('array_append'));
    expect(update).toBeTruthy(); // o id novo entra na lista que o webhook aceita
  });
  it('se o Pix antigo foi pago no último instante, marca o pedido como pago e não gera outro', async () => {
    q1.mockResolvedValue(row());
    getPayment.mockResolvedValue({ id: 555, status: 'approved' });
    expect(await regeneratePix(12, 7)).toEqual({ ok: true, state: 'paid' });
    expect(createPixPayment).not.toHaveBeenCalled();
    expect(q.mock.calls.map((c) => String(c[0])).some((s) => s.includes("payment = 'pago'"))).toBe(true);
  });
  it('limite de Pix gerados por pedido: 429', async () => {
    q1.mockResolvedValue(row({ pix_attempts: MAX_PIX_ATTEMPTS }));
    expect(await regeneratePix(12, 7)).toMatchObject({ ok: false, status: 429 });
    expect(createPixPayment).not.toHaveBeenCalled();
  });
  it('falha do Mercado Pago ao gerar: 502 com mensagem amigável', async () => {
    q1.mockResolvedValue(row());
    createPixPayment.mockRejectedValue(new Error('Mercado Pago 500'));
    expect(await regeneratePix(12, 7)).toMatchObject({ ok: false, status: 502 });
  });
});
