import { afterEach, describe, expect, it, vi } from 'vitest';
import { createPixPayment } from '@/lib/mercadopago';

afterEach(() => vi.unstubAllGlobals());

const mpOk = () => new Response(JSON.stringify({ id: 9, status: 'pending', point_of_interaction: { transaction_data: { qr_code: 'CODE', qr_code_base64: 'QR' } } }), { status: 201 });
const base = { code: 4821, total: 10, email: 'a@b.com', name: 'Ana Souza', expiresAt: new Date(Date.now() + 3600_000) };

describe('createPixPayment: referência do pagamento', () => {
  it('pedido da loja mantém external_reference e a chave de idempotência de sempre', async () => {
    const f = vi.fn(async () => mpOk());
    vi.stubGlobal('fetch', f);
    await createPixPayment({ ...base, orderId: 12, attempt: 2 });
    const [, init] = f.mock.calls[0] as any;
    expect(JSON.parse(init.body).external_reference).toBe('12');
    expect(init.headers['X-Idempotency-Key']).toBe('rayka-order-12-2');
  });

  it('link de pagamento usa a própria referência e a própria chave', async () => {
    const f = vi.fn(async () => mpOk());
    vi.stubGlobal('fetch', f);
    await createPixPayment({ ...base, orderId: 5, reference: 'link:5' });
    const [, init] = f.mock.calls[0] as any;
    expect(JSON.parse(init.body).external_reference).toBe('link:5');
    expect(init.headers['X-Idempotency-Key']).toBe('rayka-link-5-1');
  });
});
