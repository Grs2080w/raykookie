// Link de pagamento de pedido registrado à mão, contra um Postgres de verdade (precisa de TEST_DATABASE_URL; sem ela, é ignorado).
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

const TEST_DB = process.env.TEST_DATABASE_URL;
const d = TEST_DB ? describe : describe.skip;
if (TEST_DB) process.env.DATABASE_URL = TEST_DB;

let owner = 0;
const mpOn = vi.fn(() => true);
const createPixPayment = vi.fn(), getPayment = vi.fn(), cancelPayment = vi.fn(), createRefund = vi.fn(), getRefunds = vi.fn(), notifyPaid = vi.fn();
vi.mock('@/lib/auth', async (orig) => ({ ...(await orig<any>()), getUser: async () => ({ id: owner, username: 'rayka' }) }));
vi.mock('@/lib/mercadopago', async (orig) => ({
  ...(await orig<any>()),
  mpConfigured: () => mpOn(),
  validSignature: () => true,
  createPixPayment: (...a: unknown[]) => createPixPayment(...a),
  getPayment: (...a: unknown[]) => getPayment(...a),
  cancelPayment: (...a: unknown[]) => cancelPayment(...a),
  createRefund: (...a: unknown[]) => createRefund(...a),
  getRefunds: (...a: unknown[]) => getRefunds(...a),
}));
vi.mock('@/lib/push', async (orig) => ({
  ...(await orig<any>()),
  notifyPaid: (...a: unknown[]) => notifyPaid(...a),
  notifyCustomerPaid: vi.fn(), notifyOwnerRefund: vi.fn(), notifyCustomerRefund: vi.fn(), notifyCancelled: vi.fn(),
}));

d('link de pagamento (banco real)', () => {
  let q: typeof import('@/lib/db').q, q1: typeof import('@/lib/db').q1, pool: typeof import('@/lib/db').pool;
  let L: typeof import('@/lib/pay-link');
  let n = 0, other = 0, customer = 0;

  /** pedido registrado à mão: 2 × R$ 40 = R$ 80 (ou pedido do app, se `shop`) */
  async function seed(o: { status?: string; payment?: string; shop?: boolean; user?: number } = {}) {
    n++;
    let shopId: number | null = null;
    if (o.shop) {
      shopId = (await q1<{ id: number }>(
        `INSERT INTO shop_orders(customer_id, owner_id, contact_name, phone, fulfillment, scheduled_for, time_window, payment_method, items, total, public_code)
         VALUES ($1,$2,'Maria','11999999999','retirada','2026-10-20','tarde','pix','[{"id":"1","name":"Cookie","qty":1,"unit_price":6}]',6,$3) RETURNING id`,
        [customer, owner, 20000 + Math.floor(Math.random() * 70000) + n]))!.id;
    }
    return (await q1<{ id: number }>(
      `INSERT INTO orders(user_id, number, customer, product, qty, unit_price, status, payment, shop_order_id) VALUES ($1,$2,'Ana','Bolo de cenoura',2,40,$3,$4,$5) RETURNING id`,
      [o.user ?? owner, n, o.status ?? 'novo', o.payment ?? 'pendente', shopId]))!.id;
  }

  beforeAll(async () => {
    const db = await import('@/lib/db'); q = db.q; q1 = db.q1; pool = db.pool;
    L = await import('@/lib/pay-link');
    const stamp = Date.now();
    owner = (await q1<any>(`INSERT INTO users(username, password_hash) VALUES ($1, 'x') RETURNING id`, [`link-${stamp}`]))!.id;
    other = (await q1<any>(`INSERT INTO users(username, password_hash) VALUES ($1, 'x') RETURNING id`, [`link-outra-${stamp}`]))!.id;
    customer = (await q1<any>(`INSERT INTO customers(email, name) VALUES ($1, 'Maria') RETURNING id`, [`link${stamp}@x.com`]))!.id;
  });
  afterAll(async () => { await pool?.end(); });
  beforeEach(() => {
    mpOn.mockReset(); mpOn.mockReturnValue(true);
    createPixPayment.mockReset(); getPayment.mockReset(); cancelPayment.mockReset(); createRefund.mockReset(); getRefunds.mockReset(); notifyPaid.mockReset();
  });

  describe('ensurePayLink', () => {
    it('cria o link com o total do pedido por padrão e devolve a URL pública', async () => {
      const id = await seed();
      const r = await L.ensurePayLink(owner, id, null);
      expect(r).toMatchObject({ ok: true, amount: 80 });
      expect((r as any).url).toMatch(/\/pagar\/[A-Za-z0-9_-]{43}$/);
    });

    it('reabrir "Compartilhar" devolve a mesma URL', async () => {
      const id = await seed();
      const a: any = await L.ensurePayLink(owner, id, null);
      const b: any = await L.ensurePayLink(owner, id, null);
      expect(b.url).toBe(a.url);
      expect((await q('SELECT 1 FROM order_pay_links WHERE order_id = $1', [id])).length).toBe(1);
    });

    it('valor editável: aceita de 0,01 até o total (com arredondamento) e recusa fora disso', async () => {
      const id = await seed({ payment: 'sinal' });
      expect(await L.ensurePayLink(owner, id, 30.5)).toMatchObject({ ok: true, amount: 30.5 });
      expect(await L.ensurePayLink(owner, id, 10.005)).toMatchObject({ ok: true }); // arredonda a 2 casas
      expect(await L.ensurePayLink(owner, id, 80)).toMatchObject({ ok: true, amount: 80 });
      expect(await L.ensurePayLink(owner, id, 80.01)).toMatchObject({ ok: false, status: 400 });
      expect(await L.ensurePayLink(owner, id, 0)).toMatchObject({ ok: false, status: 400 });
      expect(await L.ensurePayLink(owner, id, 0.001)).toMatchObject({ ok: false, status: 400 });
    });

    it('recusa pedido de outra conta, inexistente, do app, já pago, cancelado e Mercado Pago desligado', async () => {
      expect(await L.ensurePayLink(other, await seed(), null)).toMatchObject({ ok: false, status: 404 });
      expect(await L.ensurePayLink(owner, 999999999, null)).toMatchObject({ ok: false, status: 404 });
      expect(await L.ensurePayLink(owner, await seed({ shop: true }), null)).toMatchObject({ ok: false, status: 400 });
      expect(await L.ensurePayLink(owner, await seed({ payment: 'pago' }), null)).toMatchObject({ ok: false, status: 409 });
      expect(await L.ensurePayLink(owner, await seed({ status: 'cancelado' }), null)).toMatchObject({ ok: false, status: 409 });
      mpOn.mockReturnValue(false);
      expect(await L.ensurePayLink(owner, await seed(), null)).toMatchObject({ ok: false, status: 503 });
    });
  });

  describe('payLinkView', () => {
    it('token inexistente ou fora do formato: null', async () => {
      expect(await L.payLinkView('A'.repeat(43))).toBeNull();
      expect(await L.payLinkView('curto')).toBeNull();
      expect(await L.payLinkView("x' OR '1'='1")).toBeNull();
    });

    it('link novo: aberto, com o resumo do pedido e sem Pix', async () => {
      const id = await seed();
      const url = ((await L.ensurePayLink(owner, id, 50)) as any).url as string;
      const v = await L.payLinkView(url.split('/').pop()!);
      expect(v).toMatchObject({ state: 'aberto', amount: 50, pix: null, order: { customer: 'Ana', product: 'Bolo de cenoura', qty: 2, unit_price: 40 } });
    });

    it('pedido marcado "pago" à mão ou cancelado depois de compartilhar muda o estado da página', async () => {
      const id = await seed();
      const token = (((await L.ensurePayLink(owner, id, null)) as any).url as string).split('/').pop()!;
      await q(`UPDATE orders SET payment = 'pago' WHERE id = $1`, [id]);
      expect((await L.payLinkView(token))!.state).toBe('pago');
      await q(`UPDATE orders SET payment = 'pendente', status = 'cancelado' WHERE id = $1`, [id]);
      expect((await L.payLinkView(token))!.state).toBe('cancelado');
    });

    it('pedido excluído leva o link junto (404 na página)', async () => {
      const id = await seed();
      const token = (((await L.ensurePayLink(owner, id, null)) as any).url as string).split('/').pop()!;
      await q('DELETE FROM orders WHERE id = $1', [id]);
      expect(await L.payLinkView(token)).toBeNull();
    });
  });

  /** link com valor `amount`; devolve ids e o token */
  async function seedLink(amount: number | null = null, o: Parameters<typeof seed>[0] = {}) {
    const order = await seed(o);
    const token = (((await L.ensurePayLink(owner, order, amount)) as any).url as string).split('/').pop()!;
    const link = (await q1<{ id: number }>('SELECT id FROM order_pay_links WHERE token = $1', [token]))!.id;
    return { order, token, link };
  }
  let pixN = 0;
  const fakePix = () => createPixPayment.mockImplementation(async (o: any) => {
    pixN++;
    return { paymentId: `${900000 + pixN}`, code: `CODE${pixN}`, qrBase64: 'QR', expiresAt: o.expiresAt };
  });

  describe('payLinkPix', () => {
    it('GET (payLinkView) não gera Pix; o primeiro POST gera, com referência, valor e e-mail do link', async () => {
      fakePix();
      const { order, token, link } = await seedLink(50);
      await L.payLinkView(token);
      expect(createPixPayment).not.toHaveBeenCalled();
      const r: any = await L.payLinkPix(token);
      expect(r).toMatchObject({ ok: true, state: 'renewed', pix: { qr_base64: 'QR', expired: false } });
      expect(r.pix.expires_in).toBeGreaterThan(59 * 60 - 5);
      expect(createPixPayment.mock.calls[0][0]).toMatchObject({
        orderId: link, reference: `link:${link}`, total: 50, name: 'Ana', code: expect.any(Number), email: L.payerEmail(), attempt: 1,
      });
      const row = (await q1<any>('SELECT pix_attempts, mp_payment_ids, mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!;
      expect(row).toMatchObject({ pix_attempts: 1, mp_payment_ids: [row.mp_payment_id] });
      expect((await L.payLinkView(token))!.pix).toMatchObject({ code: r.pix.code, expired: false });
      void order;
    });

    it('Pix ainda válido: o segundo POST (duplo toque) devolve o mesmo e não cria outro', async () => {
      fakePix();
      const { token } = await seedLink();
      const a: any = await L.payLinkPix(token);
      const b: any = await L.payLinkPix(token);
      expect(b).toMatchObject({ ok: true, state: 'active' });
      expect(b.pix.code).toBe(a.pix.code);
      expect(createPixPayment).toHaveBeenCalledTimes(1);
    });

    it('Pix vencido: confere o antigo no Mercado Pago, cancela se pendente e gera outro (tentativa 2)', async () => {
      fakePix(); getPayment.mockResolvedValue({ status: 'pending' });
      const { token, link } = await seedLink();
      const a: any = await L.payLinkPix(token);
      const oldId = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_id;
      await q(`UPDATE order_pay_links SET pix_expires_at = now() - interval '1 minute' WHERE id = $1`, [link]);
      const b: any = await L.payLinkPix(token);
      expect(b.state).toBe('renewed');
      expect(b.pix.code).not.toBe(a.pix.code);
      expect(cancelPayment).toHaveBeenCalledWith(oldId);
      expect(createPixPayment.mock.calls[1][0]).toMatchObject({ attempt: 2 });
      expect((await q1<any>('SELECT mp_payment_ids FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_ids).toHaveLength(2);
    });

    it('Pix vencido que foi pago no último instante: marca pago e não gera outro', async () => {
      fakePix();
      const { order, token, link } = await seedLink();
      await L.payLinkPix(token);
      const oldId = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_id;
      await q(`UPDATE order_pay_links SET pix_expires_at = now() - interval '1 minute' WHERE id = $1`, [link]);
      getPayment.mockResolvedValue({ id: Number(oldId), status: 'approved', transaction_amount: 80 });
      expect(await L.payLinkPix(token)).toEqual({ ok: true, state: 'paid' });
      expect(createPixPayment).toHaveBeenCalledTimes(1);
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pago');
    });

    it('limite de Pix por link: 429', async () => {
      fakePix(); getPayment.mockResolvedValue({ status: 'pending' });
      const { token, link } = await seedLink();
      await q(`UPDATE order_pay_links SET pix_attempts = 10, pix_expires_at = now() - interval '1 minute', pix_qr_code = 'X' WHERE id = $1`, [link]);
      expect(await L.payLinkPix(token)).toMatchObject({ ok: false, status: 429 });
    });

    it('pedido pago à mão (dinheiro) depois de compartilhar: "paid", sem Pix; cancelado: 409; token ruim: 404; MP desligado: 503', async () => {
      fakePix();
      const paid = await seedLink();
      await q(`UPDATE orders SET payment = 'pago' WHERE id = $1`, [paid.order]);
      expect(await L.payLinkPix(paid.token)).toEqual({ ok: true, state: 'paid' });
      const canceled = await seedLink();
      await q(`UPDATE orders SET status = 'cancelado' WHERE id = $1`, [canceled.order]);
      expect(await L.payLinkPix(canceled.token)).toMatchObject({ ok: false, status: 409 });
      expect(await L.payLinkPix('A'.repeat(43))).toMatchObject({ ok: false, status: 404 });
      expect(await L.payLinkPix('curto')).toMatchObject({ ok: false, status: 404 });
      const off = await seedLink();
      mpOn.mockReturnValue(false);
      expect(await L.payLinkPix(off.token)).toMatchObject({ ok: false, status: 503 });
      expect(createPixPayment).not.toHaveBeenCalled();
    });

    it('falha do Mercado Pago ao criar o Pix: 502 e nada gravado', async () => {
      createPixPayment.mockRejectedValue(new Error('MP fora do ar'));
      const { token, link } = await seedLink();
      vi.spyOn(console, 'error').mockImplementation(() => {});
      expect(await L.payLinkPix(token)).toMatchObject({ ok: false, status: 502 });
      expect((await q1<any>('SELECT pix_attempts, pix_qr_code FROM order_pay_links WHERE id = $1', [link]))).toMatchObject({ pix_attempts: 0, pix_qr_code: '' });
    });

    it('mudar o valor depois de gerar o Pix cancela o pendente e a página volta a pedir "Pagar com Pix"', async () => {
      fakePix();
      const { order, token, link } = await seedLink(80);
      await L.payLinkPix(token);
      const oldId = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_id;
      expect(await L.ensurePayLink(owner, order, 50)).toMatchObject({ ok: true, amount: 50 });
      expect(cancelPayment).toHaveBeenCalledWith(oldId);
      expect((await L.payLinkView(token))).toMatchObject({ amount: 50, pix: null });
      // mesmo valor de novo: não mexe no Pix
      await L.payLinkPix(token);
      cancelPayment.mockClear();
      await L.ensurePayLink(owner, order, 50);
      expect(cancelPayment).not.toHaveBeenCalled();
    });

    it('corrida: o webhook paga o link entre a leitura e a consulta ao MP: "paid" e nenhum Pix novo', async () => {
      fakePix();
      const { token, link } = await seedLink(80);
      await L.payLinkPix(token);
      const oldId = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_id;
      await q(`UPDATE order_pay_links SET pix_expires_at = now() - interval '1 minute' WHERE id = $1`, [link]);
      getPayment.mockImplementation(async () => {
        await L.markLinkPaid(link, oldId, 80);
        return { id: Number(oldId), status: 'approved', transaction_amount: 80 };
      });
      expect(await L.payLinkPix(token)).toEqual({ ok: true, state: 'paid' });
      expect(createPixPayment).toHaveBeenCalledTimes(1);
    });

    it('Mercado Pago fora ao conferir o Pix vencido: avisa no log e gera o novo (tentativa 2)', async () => {
      fakePix();
      const { token, link } = await seedLink(80);
      await L.payLinkPix(token);
      await q(`UPDATE order_pay_links SET pix_expires_at = now() - interval '1 minute' WHERE id = $1`, [link]);
      getPayment.mockRejectedValue(new Error('MP offline'));
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      expect(await L.payLinkPix(token)).toMatchObject({ ok: true, state: 'renewed' });
      expect(createPixPayment.mock.calls[1][0]).toMatchObject({ attempt: 2 });
      expect(warn).toHaveBeenCalled();
    });

    it('Pix antigo aprovado com valor menor que o cobrado: não paga, avisa no log e gera o novo', async () => {
      fakePix();
      const { order, token, link } = await seedLink(80);
      await L.payLinkPix(token);
      const oldId = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_id;
      await q(`UPDATE order_pay_links SET pix_expires_at = now() - interval '1 minute' WHERE id = $1`, [link]);
      getPayment.mockResolvedValue({ id: Number(oldId), status: 'approved', transaction_amount: 79 });
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      expect(await L.payLinkPix(token)).toMatchObject({ ok: true, state: 'renewed' });
      expect(warn).toHaveBeenCalled();
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pendente');
    });

    it('e-mail do pagador: padrão pelo domínio do site, ou o do .env', () => {
      const old = process.env.PAY_LINK_PAYER_EMAIL;
      delete process.env.PAY_LINK_PAYER_EMAIL;
      expect(L.payerEmail()).toMatch(/^pagamentos@[a-z0-9.-]+\.[a-z]+$/);
      expect(L.payerEmail()).not.toContain('www.');
      process.env.PAY_LINK_PAYER_EMAIL = 'loja@exemplo.com';
      expect(L.payerEmail()).toBe('loja@exemplo.com');
      if (old === undefined) delete process.env.PAY_LINK_PAYER_EMAIL; else process.env.PAY_LINK_PAYER_EMAIL = old;
    });
  });

  describe('markLinkPaid / handleLinkPayment', () => {
    async function withPix(amount: number | null = null) {
      fakePix();
      const s = await seedLink(amount);
      await L.payLinkPix(s.token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [s.link]))!.mp_payment_id as string;
      return { ...s, mp };
    }
    const approved = (mp: string, amount: number, extra: object = {}) => ({ id: Number(mp), status: 'approved', transaction_amount: amount, external_reference: '', ...extra }) as any;

    describe('promoção a pedido do app', () => {
      const shopOf = (order: number) => q1<any>('SELECT so.*, c.guest, c.email, c.password_hash, c.google_sub FROM orders o JOIN shop_orders so ON so.id = o.shop_order_id JOIN customers c ON c.id = so.customer_id WHERE o.id = $1', [order]);

      it('ao pagar: cria cliente convidado e o pedido do app, ligados, com o valor pago e uma linha de item', async () => {
        const { order, link, mp } = await withPix(50); // pedido de 2 × R$ 40, link de R$ 50 (sinal)
        expect(await L.markLinkPaid(link, mp, 50)).toBe(order);
        const so = await shopOf(order);
        expect(so).toMatchObject({ guest: true, manual: true, contact_name: 'Ana', total: 50, payment_method: 'pix', fulfillment: 'retirada', phone: '', paid_mp_id: mp, password_hash: null, google_sub: null });
        expect(so.email).toBe(`link-${link}@convidado.invalid`);
        expect(so.items).toEqual([{ id: `manual-${order}`, name: '2 × Bolo de cenoura', qty: 1, unit_price: 50 }]);
        expect(so.mp_payment_ids).toContain(mp);
        expect(so.public_code).toBeGreaterThanOrEqual(1000);
        expect((await q1<any>('SELECT shop_order_id FROM order_pay_links WHERE id = $1', [link]))!.shop_order_id).toBe(so.id);
        expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pago');
        expect((await q('SELECT type FROM order_events WHERE shop_order_id = $1', [so.id])).map((e: any) => e.type)).toContain('pago');
        expect(notifyPaid).toHaveBeenCalledWith(owner, expect.objectContaining({ id: so.id, code: so.public_code, customer: 'Ana', total: 50, items: 1 }));
      });

      it('quantidade fracionada vira "0,5 × produto"', async () => {
        fakePix();
        const s = await seedLink(null);
        await q('UPDATE orders SET qty = 0.5, unit_price = 30 WHERE id = $1', [s.order]);
        await q('UPDATE order_pay_links SET amount = 15 WHERE id = $1', [s.link]);
        await L.payLinkPix(s.token);
        const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [s.link]))!.mp_payment_id as string;
        await L.markLinkPaid(s.link, mp, 15);
        expect((await shopOf(s.order)).items[0].name).toBe('0,5 × Bolo de cenoura');
      });

      it('idempotente: repetir o webhook, a conciliação ou promoteLink não cria outro pedido nem outro convidado', async () => {
        const { order, link, mp } = await withPix(80);
        await L.markLinkPaid(link, mp, 80);
        const first = (await shopOf(order)).id;
        expect(await L.markLinkPaid(link, mp, 80)).toBeNull();
        expect(await L.promoteLink(link)).toBe(false);
        expect((await shopOf(order)).id).toBe(first);
        expect(Number((await q1<any>(`SELECT count(*) AS n FROM customers WHERE email = $1`, [`link-${link}@convidado.invalid`]))!.n)).toBe(1);
        expect(Number((await q1<any>('SELECT count(*) AS n FROM shop_orders so JOIN orders o ON o.shop_order_id = so.id WHERE o.id = $1', [order]))!.n)).toBe(1);
      });

      it('duas confirmações ao mesmo tempo (webhook + conciliação): um só pedido do app', async () => {
        const { order, link, mp } = await withPix(80);
        await Promise.all([L.markLinkPaid(link, mp, 80), L.markLinkPaid(link, mp, 80), L.promoteLink(link)]);
        expect(notifyPaid).toHaveBeenCalledTimes(1);
        expect(Number((await q1<any>('SELECT count(*) AS n FROM shop_orders so JOIN orders o ON o.shop_order_id = so.id WHERE o.id = $1', [order]))!.n)).toBe(1);
        expect(Number((await q1<any>(`SELECT count(*) AS n FROM customers WHERE email = $1`, [`link-${link}@convidado.invalid`]))!.n)).toBe(1);
      });

      it('pedido já "pago" à mão e o Pix também chega: promove, avisa "em dobro" e não devolve sozinho', async () => {
        const { order, link, mp } = await withPix(80);
        await q(`UPDATE orders SET payment = 'pago' WHERE id = $1`, [order]);
        vi.spyOn(console, 'warn').mockImplementation(() => {});
        await L.markLinkPaid(link, mp, 80);
        expect(await shopOf(order)).toMatchObject({ manual: true, cancelled_at: null });
        expect(notifyPaid).toHaveBeenCalledWith(owner, expect.objectContaining({ customer: expect.stringContaining('pagamento em dobro') }));
        expect(createRefund).not.toHaveBeenCalled();
      });

      it('pedido cancelado e o Pix chega: promove já cancelado, devolve tudo sozinho e o pedido segue cancelado (payment não vira "pago")', async () => {
        const { order, link, mp } = await withPix(80);
        await q(`UPDATE orders SET status = 'cancelado' WHERE id = $1`, [order]);
        vi.spyOn(console, 'warn').mockImplementation(() => {});
        createRefund.mockResolvedValue({ id: 77, status: 'approved', amount: 80 });
        await L.markLinkPaid(link, mp, 80);
        const so = await shopOf(order);
        expect(so.cancelled_at).toBeTruthy();
        expect(so.cancelled_by).toBe('loja');
        expect((await q1<any>('SELECT payment, status FROM orders WHERE id = $1', [order]))).toMatchObject({ status: 'cancelado', payment: 'pendente' });
        expect(createRefund).toHaveBeenCalledTimes(1);
        expect(createRefund.mock.calls[0][0]).toBe(mp);
        expect((await q1<any>(`SELECT kind, reason_code FROM refunds WHERE shop_order_id = $1`, [so.id]))).toMatchObject({ kind: 'sistema', reason_code: 'pago_apos_cancelamento' });
      });

      it('pedido entregue antes de pagar: o prazo de 48h para pedir devolução começa a contar (delivered_at preenchido)', async () => {
        const { order, link, mp } = await withPix(80);
        await q(`UPDATE orders SET status = 'entregue' WHERE id = $1`, [order]);
        await L.markLinkPaid(link, mp, 80);
        expect((await shopOf(order)).delivered_at).toBeTruthy();
      });

      it('promoteLink: link antigo já pago (sem pedido do app) é promovido; link não pago não', async () => {
        const { order, link, mp } = await withPix(80);
        await q(`UPDATE order_pay_links SET paid_at = now(), paid_mp_id = $2 WHERE id = $1`, [link, mp]);
        await q(`UPDATE orders SET payment = 'pago' WHERE id = $1`, [order]);
        notifyPaid.mockClear();
        expect(await L.promoteLink(link)).toBe(true);
        expect((await shopOf(order)).manual).toBe(true);
        expect(notifyPaid).not.toHaveBeenCalled(); // histórico: sem push de "pagamento em dobro"
        expect(createRefund).not.toHaveBeenCalled();
        const unpaid = await seedLink(null);
        expect(await L.promoteLink(unpaid.link)).toBe(false);
      });

      it('promoteLink de link antigo cujo pedido está cancelado: promove cancelado, sem push e sem devolução automática', async () => {
        const { order, link, mp } = await withPix(80);
        await q(`UPDATE order_pay_links SET paid_at = now(), paid_mp_id = $2 WHERE id = $1`, [link, mp]);
        await q(`UPDATE orders SET status = 'cancelado', payment = 'pago' WHERE id = $1`, [order]);
        notifyPaid.mockClear();
        expect(await L.promoteLink(link)).toBe(true);
        expect(await shopOf(order)).toMatchObject({ cancelled_by: 'loja' });
        expect(notifyPaid).not.toHaveBeenCalled();
        expect(createRefund).not.toHaveBeenCalled();
      });

      it('varredura: pedido promovido cancelado pela loja sem nenhum estorno é devolvido uma só vez; o que já tem estorno não é tocado', async () => {
        vi.spyOn(console, 'warn').mockImplementation(() => {});
        vi.spyOn(console, 'error').mockImplementation(() => {});
        // órfão: cancelado, pago, sem linha em refunds (o estorno automático falhou depois do commit)
        const a = await withPix(80);
        await q(`UPDATE orders SET status = 'cancelado' WHERE id = $1`, [a.order]);
        createRefund.mockRejectedValue(new Error('MP fora do ar'));
        await L.markLinkPaid(a.link, a.mp, 80);
        const soA = await shopOf(a.order);
        await q('DELETE FROM refunds WHERE shop_order_id = $1', [soA.id]);
        // outro: cancelado pelo app depois, já com linha de estorno
        const b = await withPix(80);
        await L.markLinkPaid(b.link, b.mp, 80);
        const soB = await shopOf(b.order);
        await q(`UPDATE shop_orders SET cancelled_at = now(), cancelled_by = 'loja' WHERE id = $1`, [soB.id]);
        await q(`INSERT INTO refunds(shop_order_id, owner_id, customer_id, kind, status, reason_code, description, requested_amount, approved_amount, decided_at)
                 VALUES ($1,$2,$3,'sistema','processando','outro','x',80,80,now())`, [soB.id, owner, soB.customer_id]);
        createRefund.mockReset(); createRefund.mockResolvedValue({ id: 88, status: 'approved', amount: 80 });
        getPayment.mockResolvedValue({ id: 1, status: 'pending', transaction_amount: 80, external_reference: '' });
        await L.reconcileLinks();
        expect(createRefund).toHaveBeenCalledTimes(1);
        expect(createRefund.mock.calls[0][0]).toBe(a.mp);
        expect((await q1<any>(`SELECT kind, reason_code FROM refunds WHERE shop_order_id = $1`, [soA.id]))).toMatchObject({ kind: 'sistema', reason_code: 'pago_apos_cancelamento' });
        expect(Number((await q1<any>('SELECT count(*) AS n FROM refunds WHERE shop_order_id = $1', [soB.id]))!.n)).toBe(1);
        createRefund.mockClear();
        await L.reconcileLinks();
        expect(createRefund).not.toHaveBeenCalled();
      });

      it('getCustomerOrders do convidado devolve o pedido no formato do cliente, com manual=true e pedido de devolução liberado após a entrega', async () => {
        const { order, link, mp } = await withPix(80);
        await L.markLinkPaid(link, mp, 80);
        const guest = (await shopOf(order)).customer_id;
        const { getCustomerOrders } = await import('@/lib/shop-queries');
        const [o] = await getCustomerOrders(guest);
        expect(o).toMatchObject({ manual: true, total: 80, paid: true, status: 'novo', can_cancel: true });
        expect(o.items).toHaveLength(1);
      });
    });

    it('pagamento aprovado do próprio link com valor suficiente: marca o link e o pedido; a repetição não faz nada', async () => {
      const { order, link, mp } = await withPix(80);
      expect(await L.markLinkPaid(link, mp, 80)).toBe(order);
      const l = (await q1<any>('SELECT paid_at, paid_mp_id FROM order_pay_links WHERE id = $1', [link]))!;
      expect(l.paid_at).toBeTruthy(); expect(l.paid_mp_id).toBe(mp);
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pago');
      expect(await L.markLinkPaid(link, mp, 80)).toBeNull();
    });

    it('valor menor que o cobrado ou pagamento de outro link: não marca', async () => {
      const a = await withPix(80), b = await withPix(80);
      expect(await L.markLinkPaid(a.link, a.mp, 79.99)).toBeNull();
      expect(await L.markLinkPaid(a.link, b.mp, 80)).toBeNull();
      expect((await q1<any>('SELECT paid_at FROM order_pay_links WHERE id = $1', [a.link]))!.paid_at).toBeNull();
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [a.order]))!.payment).toBe('pendente');
    });

    it('valor acima do cobrado (pagou a mais) é aceito', async () => {
      const { link, mp } = await withPix(50);
      expect(await L.markLinkPaid(link, mp, 80)).not.toBeNull();
    });

    it('handleLinkPayment: aprovado marca; estornado/pendente é ignorado; id ruim não quebra; sem efeito vira aviso no log', async () => {
      const { order, link, mp } = await withPix(80);
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      expect(await L.handleLinkPayment({ ...approved(mp, 80), status: 'refunded' }, link)).toMatchObject({ ok: true, status: 'refunded' });
      expect(await L.handleLinkPayment({ ...approved(mp, 80), status: 'pending' }, link)).toMatchObject({ ok: true, status: 'pending' });
      expect(await L.handleLinkPayment(approved(mp, 80), Number.NaN)).toMatchObject({ ok: true });
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pendente');
      expect(await L.handleLinkPayment(approved(mp, 79), link)).toMatchObject({ ok: true, paid: false });
      expect(warn).toHaveBeenCalled();
      expect(await L.handleLinkPayment(approved(mp, 80), link)).toMatchObject({ ok: true, paid: true });
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pago');
      expect(await L.handleLinkPayment(approved(mp, 80), link)).toMatchObject({ ok: true, paid: false }); // reenvio do webhook
    });

    it('estorno/chargeback de pagamento de link promovido: concilia a devolução (antes era ignorado)', async () => {
      const { order, link, mp } = await withPix(80);
      await L.markLinkPaid(link, mp, 80);
      const so = (await q1<any>('SELECT shop_order_id FROM order_pay_links WHERE id = $1', [link]))!.shop_order_id as number;
      getRefunds.mockResolvedValue([{ id: 31, amount: 20, status: 'approved' }]);
      await L.handleLinkPayment({ id: Number(mp), status: 'refunded', transaction_amount: 80, external_reference: `link:${link}`, transaction_amount_refunded: 20 } as any, link);
      expect(getRefunds).toHaveBeenCalledWith(mp);
      expect((await q1<any>('SELECT refunded_total FROM shop_orders WHERE id = $1', [so]))!.refunded_total).toBe(20);
      void order;
    });

    it('estorno de link que ainda não foi promovido (nunca pago) continua ignorado', async () => {
      const { link, mp } = await withPix(80);
      getRefunds.mockClear();
      expect(await L.handleLinkPayment({ id: Number(mp), status: 'refunded', transaction_amount: 80, external_reference: `link:${link}` } as any, link)).toMatchObject({ ok: true, status: 'refunded' });
      expect(getRefunds).not.toHaveBeenCalled();
    });
  });

  describe('pagamento fora de hora (F2)', () => {
    async function withPix2() {
      fakePix();
      const s = await seedLink(80);
      await L.payLinkPix(s.token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [s.link]))!.mp_payment_id as string;
      return { ...s, mp };
    }
    it('pedido já "pago" à mão e Pix do link aprovado: grava paid_at, segue pago e registra no log', async () => {
      const { order, link, mp } = await withPix2();
      await q(`UPDATE orders SET payment = 'pago' WHERE id = $1`, [order]);
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      expect(await L.markLinkPaid(link, mp, 80)).toBe(order);
      expect((await q1<any>('SELECT paid_at, paid_mp_id FROM order_pay_links WHERE id = $1', [link]))!.paid_at).toBeTruthy();
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pago');
      expect(warn).toHaveBeenCalledWith(expect.stringContaining('cancelado ou já pago'), expect.objectContaining({ linkId: link, orderId: order, mpPaymentId: mp }));
      warn.mockRestore();
    });
    it('pedido cancelado e Pix aprovado: grava paid_at, segue cancelado, payment não vira "pago" e registra no log', async () => {
      const { order, link, mp } = await withPix2();
      await q(`UPDATE orders SET status = 'cancelado' WHERE id = $1`, [order]);
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      await L.markLinkPaid(link, mp, 80);
      expect((await q1<any>('SELECT paid_at FROM order_pay_links WHERE id = $1', [link]))!.paid_at).toBeTruthy();
      const o = (await q1<any>('SELECT status, payment FROM orders WHERE id = $1', [order]))!;
      expect(o.status).toBe('cancelado'); expect(o.payment).not.toBe('pago');
      expect(warn).toHaveBeenCalledWith(expect.stringContaining('cancelado ou já pago'), expect.objectContaining({ linkId: link, status: 'cancelado' }));
      warn.mockRestore();
    });
  });

  describe('reconcileLinks (F3)', () => {
    it('Pix aprovado cujo webhook se perdeu é aplicado; link já pago não é reprocessado', async () => {
      fakePix();
      const a = await seedLink(80); await L.payLinkPix(a.token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [a.link]))!.mp_payment_id as string;
      getPayment.mockImplementation(async (id: string) => ({ id: Number(id), status: id === mp ? 'approved' : 'pending', transaction_amount: 80, external_reference: '' }));
      expect(await L.reconcileLinks()).toBeGreaterThanOrEqual(1);
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [a.order]))!.payment).toBe('pago');
      expect((await q1<any>('SELECT paid_at FROM order_pay_links WHERE id = $1', [a.link]))!.paid_at).toBeTruthy();
      getPayment.mockClear();
      await L.reconcileLinks();
      expect(getPayment.mock.calls.map((c) => c[0])).not.toContain(mp);
    });
  });

  describe('valor do pedido baixou (F5)', () => {
    it('link de 80 e pedido que passou a valer 60: 409 e não chama o Mercado Pago', async () => {
      fakePix();
      const { order, token } = await seedLink(80);
      await q('UPDATE orders SET unit_price = 30 WHERE id = $1', [order]);
      expect(await L.payLinkPix(token)).toMatchObject({ ok: false, status: 409, error: 'O valor deste pedido mudou. Peça um novo link para a loja.' });
      expect(createPixPayment).not.toHaveBeenCalled();
    });
  });

  describe('rotas HTTP', () => {
    const ctx = (p: Record<string, string>) => ({ params: Promise.resolve(p) }) as any;
    const post = (body?: unknown) => new Request('http://x/api', { method: 'POST', body: body == null ? undefined : JSON.stringify(body) });

    it('POST /api/orders/:id/pagamento: devolve url e valor; aceita texto pt-BR; recusa valor ruim e pedido alheio', async () => {
      const R = await import('@/app/api/orders/[id]/pagamento/route');
      const id = await seed();
      const ok = await R.POST(post({ amount: '30,5' }), ctx({ id: String(id) }));
      expect(ok.status).toBe(200);
      expect(await ok.json()).toMatchObject({ amount: 30.5, url: expect.stringMatching(/\/pagar\/[A-Za-z0-9_-]{43}$/) });
      expect((await R.POST(post({}), ctx({ id: String(id) }))).status).toBe(200); // sem valor: total
      expect((await R.POST(post({ amount: 'abc' }), ctx({ id: String(id) }))).status).toBe(400);
      expect((await R.POST(post({ amount: '80,01' }), ctx({ id: String(id) }))).status).toBe(400);
      expect((await R.POST(post({ amount: '-5' }), ctx({ id: String(id) }))).status).toBe(400);
      expect((await R.POST(post({}), ctx({ id: String(await seed({ user: other })) }))).status).toBe(404);
      expect((await R.POST(post({}), ctx({ id: 'x' }))).status).toBe(404);
    });

    it('rotas públicas: GET resume sem gerar Pix; POST gera; token ruim é 404', async () => {
      fakePix();
      const G = await import('@/app/api/pagar/[token]/route');
      const P = await import('@/app/api/pagar/[token]/pix/route');
      const { token } = await seedLink(40);
      const g = await G.GET(new Request('http://x/api'), ctx({ token }));
      expect(g.status).toBe(200);
      expect(await g.json()).toMatchObject({ state: 'aberto', amount: 40, pix: null });
      expect(createPixPayment).not.toHaveBeenCalled();
      const p = await P.POST(post(), ctx({ token }));
      expect(p.status).toBe(200);
      expect(await p.json()).toMatchObject({ ok: true, state: 'renewed', pix: { code: expect.any(String) } });
      expect((await G.GET(new Request('http://x/api'), ctx({ token: 'A'.repeat(43) }))).status).toBe(404);
      expect((await P.POST(post(), ctx({ token: 'A'.repeat(43) }))).status).toBe(404);
      const bad = await P.POST(post(), ctx({ token: 'curto' }));
      expect(bad.status).toBe(404);
      expect((await bad.json()).error).toBeTruthy();
    });
  });

  describe('webhook do Mercado Pago com link de pagamento', () => {
    const hook = (id: string) => new Request('http://x/api/webhooks/mercadopago', { method: 'POST', body: JSON.stringify({ type: 'payment', data: { id } }) });
    beforeEach(() => { process.env.MP_WEBHOOK_SECRET = 's'; process.env.MP_ACCESS_TOKEN = 't'; });

    it('aprovado: marca o pedido do link como pago; o reenvio da notificação não repete nada', async () => {
      fakePix();
      const W = await import('@/app/api/webhooks/mercadopago/route');
      const { order, token, link } = await seedLink(80);
      await L.payLinkPix(token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_id as string;
      getPayment.mockResolvedValue({ id: Number(mp), status: 'approved', transaction_amount: 80, external_reference: `link:${link}` });
      expect((await W.POST(hook(mp))).status).toBe(200);
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pago');
      const first = (await q1<any>('SELECT paid_at FROM order_pay_links WHERE id = $1', [link]))!.paid_at.getTime();
      expect((await W.POST(hook(mp))).status).toBe(200);
      expect((await q1<any>('SELECT paid_at FROM order_pay_links WHERE id = $1', [link]))!.paid_at.getTime()).toBe(first);
      expect((await L.payLinkView(token))!.state).toBe('pago');
    });

    it('valor menor que o cobrado, pendente ou estornado: responde 200 mas não marca pago', async () => {
      fakePix();
      const W = await import('@/app/api/webhooks/mercadopago/route');
      const { order, token, link } = await seedLink(80);
      await L.payLinkPix(token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_id as string;
      vi.spyOn(console, 'warn').mockImplementation(() => {});
      for (const pay of [{ status: 'approved', transaction_amount: 79 }, { status: 'pending', transaction_amount: 80 }, { status: 'refunded', transaction_amount: 80 }]) {
        getPayment.mockResolvedValue({ id: Number(mp), external_reference: `link:${link}`, ...pay });
        expect((await W.POST(hook(mp))).status).toBe(200);
      }
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pendente');
    });
  });


  describe('rotas do cliente por token', () => {
    const ctx = (token: string) => ({ params: Promise.resolve({ token }) }) as any;
    const json = (body: unknown) => new Request('http://x/api', { method: 'POST', body: JSON.stringify(body) });
    async function paidLink(amount = 80) {
      fakePix();
      const s = await seedLink(amount);
      await L.payLinkPix(s.token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [s.link]))!.mp_payment_id as string;
      await L.markLinkPaid(s.link, mp, amount);
      return { ...s, mp };
    }

    it('payLinkView de link pago traz shop_order no formato do cliente; antes de pagar, null', async () => {
      const open = await seedLink(80);
      expect((await L.payLinkView(open.token))!.shop_order).toBeNull();
      const p = await paidLink();
      const v = await L.payLinkView(p.token);
      expect(v!.state).toBe('pago');
      expect(v!.shop_order).toMatchObject({ manual: true, total: 80, paid: true, can_cancel: true });
    });

    it('linkGuest: só para link pago; promove link antigo na hora; token ruim/de link não pago → null', async () => {
      const open = await seedLink(80);
      expect(await L.linkGuest(open.token)).toBeNull();
      expect(await L.linkGuest('curto')).toBeNull();
      expect(await L.linkGuest('A'.repeat(43))).toBeNull();
      const p = await paidLink();
      expect(await L.linkGuest(p.token)).toMatchObject({ customerId: expect.any(Number), shopOrderId: expect.any(Number) });
      // link antigo: pago, sem pedido do app
      fakePix();
      const old = await seedLink(80);
      await L.payLinkPix(old.token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [old.link]))!.mp_payment_id as string;
      await q(`UPDATE order_pay_links SET paid_at = now(), paid_mp_id = $2 WHERE id = $1`, [old.link, mp]);
      await q(`UPDATE orders SET payment = 'pago' WHERE id = $1`, [old.order]);
      expect(await L.linkGuest(old.token)).toMatchObject({ shopOrderId: expect.any(Number) });
    });

    it('cancelar: pedido pago e não iniciado cancela e devolve o Pix; token de outro link não age neste pedido', async () => {
      const a = await paidLink(), b = await paidLink();
      createRefund.mockResolvedValue({ id: 90, status: 'approved', amount: 80 });
      const C = await import('@/app/api/pagar/[token]/cancelar/route');
      const r = await C.POST(json({ reason_code: 'nao_preciso', reason: '' }), ctx(a.token));
      expect(r.status).toBe(200);
      expect(await r.json()).toMatchObject({ ok: true, state: 'cancelled', refunded: 80 });
      expect((await q1<any>('SELECT status FROM orders WHERE id = $1', [a.order]))!.status).toBe('cancelado');
      expect((await q1<any>('SELECT status FROM orders WHERE id = $1', [b.order]))!.status).not.toBe('cancelado'); // o outro link não foi tocado
      expect((await C.POST(json({}), ctx('A'.repeat(43)))).status).toBe(404);
      expect((await C.POST(json({}), ctx('curto'))).status).toBe(404);
      const unpaid = await seedLink(80);
      expect((await C.POST(json({}), ctx(unpaid.token))).status).toBe(404);
    });

    it('pedir devolução (pedido entregue, com foto) e desistir, pelo token', async () => {
      const p = await paidLink();
      await q(`UPDATE orders SET status = 'entregue' WHERE id = $1`, [p.order]);
      const { syncDelivered } = await import('@/lib/order-sync');
      await syncDelivered((await L.linkGuest(p.token))!.shopOrderId);
      const D = await import('@/app/api/pagar/[token]/devolucao/route');
      const fd = new FormData();
      fd.set('reason_code', 'avariado'); fd.set('description', 'Chegou todo amassado e derretido.');
      fd.set('items', JSON.stringify([{ id: `manual-${p.order}`, qty: 1 }]));
      fd.append('fotos', new File([Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(40)])], 'a.png', { type: 'image/png' }));
      const made = await D.POST(new Request('http://x/api', { method: 'POST', body: fd }), ctx(p.token));
      expect(made.status).toBe(201);
      const so = (await L.linkGuest(p.token))!.shopOrderId;
      expect((await q1<any>(`SELECT status, requested_amount FROM refunds WHERE shop_order_id = $1`, [so]))).toMatchObject({ status: 'solicitado', requested_amount: 80 });
      expect((await L.payLinkView(p.token))!.shop_order!.refunds[0]).toMatchObject({ status: 'solicitado' });
      const gone = await D.DELETE(new Request('http://x/api', { method: 'DELETE' }), ctx(p.token));
      expect(gone.status).toBe(200);
      expect((await q1<any>(`SELECT status FROM refunds WHERE shop_order_id = $1`, [so]))!.status).toBe('desistiu');
      expect((await D.POST(new Request('http://x/api', { method: 'POST', body: new FormData() }), ctx('A'.repeat(43)))).status).toBe(404);
    });

    it('a confeiteira aprova a devolução do pedido promovido e o estorno sai pelo pagamento do link', async () => {
      const p = await paidLink();
      await q(`UPDATE orders SET status = 'entregue' WHERE id = $1`, [p.order]);
      const so = (await L.linkGuest(p.token))!.shopOrderId;
      const { syncDelivered } = await import('@/lib/order-sync');
      await syncDelivered(so);
      const D = await import('@/app/api/pagar/[token]/devolucao/route');
      const fd = new FormData();
      fd.set('reason_code', 'avariado'); fd.set('description', 'Chegou todo amassado e derretido.'); fd.set('items', JSON.stringify([{ id: `manual-${p.order}`, qty: 1 }]));
      fd.append('fotos', new File([Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(40)])], 'a.png', { type: 'image/png' }));
      await D.POST(new Request('http://x/api', { method: 'POST', body: fd }), ctx(p.token));
      const rid = (await q1<any>('SELECT id FROM refunds WHERE shop_order_id = $1', [so]))!.id as number;
      createRefund.mockResolvedValue({ id: 91, status: 'approved', amount: 80 });
      const { decideRefund } = await import('@/lib/refunds');
      expect(await decideRefund(rid, owner, { approve: true })).toMatchObject({ ok: true, status: 'aprovado' });
      expect(createRefund.mock.calls.at(-1)![0]).toBe(p.mp);
    });
  });

  describe('isolamento do convidado', () => {
    it('não entra por e-mail/senha nem pelo Google', async () => {
      fakePix();
      const s = await seedLink(80);
      await L.payLinkPix(s.token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [s.link]))!.mp_payment_id as string;
      await L.markLinkPaid(s.link, mp, 80);
      const email = `link-${s.link}@convidado.invalid`;
      const A = await import('@/lib/customer-auth');
      expect(await A.loginCustomer(email, 'qualquer')).toEqual({ error: 'invalid' });
      await expect(A.upsertGoogleCustomer({ sub: `sub-${Date.now()}`, email, name: 'Intruso' })).rejects.toMatchObject({ code: '23505' }); // não captura o convidado (e o e-mail já está em uso)
      expect((await q1<any>('SELECT google_sub, guest FROM customers WHERE lower(email) = $1', [email]))).toMatchObject({ google_sub: null, guest: true });
    });
  });
  describe('rodada final da revisão (F1-F8)', () => {
    async function promoted(o: { amount?: number; payment?: string; status?: string } = {}) {
      fakePix();
      const amount = o.amount ?? 80;
      const s = await seedLink(amount, { status: o.status });
      await L.payLinkPix(s.token);
      if (o.payment) await q(`UPDATE orders SET payment = $2 WHERE id = $1`, [s.order, o.payment]); // marcado à mão depois de compartilhar
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [s.link]))!.mp_payment_id as string;
      await L.markLinkPaid(s.link, mp, amount);
      const so = (await q1<any>('SELECT shop_order_id FROM orders WHERE id = $1', [s.order]))!.shop_order_id as number;
      return { ...s, mp, so };
    }

    it('F1: o e-mail técnico do convidado não passa em isEmail nem no cadastro', async () => {
      const { isEmail } = await import('@shared/shop');
      expect(isEmail('link-1@convidado.invalid')).toBe(false);
      expect(isEmail('a@b.INVALID')).toBe(false);
      expect(isEmail('a@b.com')).toBe(true);
      const R = await import('@/app/api/cliente/cadastro/route');
      const email = `link-${900000 + Date.now() % 100000}@convidado.invalid`;
      const res = await R.POST(new Request('http://x/api', { method: 'POST', body: JSON.stringify({ name: 'Intruso', email, password: 'senhaboa123' }) }));
      expect(res.status).toBe(400);
      expect(await q1('SELECT 1 FROM customers WHERE email = $1', [email])).toBeNull();
    });

    it('F2: "Devolver valor" do valor todo num pedido manual (pagamento em dobro) devolve sem encerrar o pedido', async () => {
      const p = await promoted({ payment: 'pago' });
      createRefund.mockResolvedValue({ id: 71, status: 'approved', amount: 80 });
      const { ownerRefund } = await import('@/lib/refunds');
      expect(await ownerRefund(p.so, owner, '80', 'Pix em dobro')).toMatchObject({ ok: true });
      expect((await q1<any>('SELECT refunded_total::float8 AS r, cancelled_at FROM shop_orders WHERE id = $1', [p.so]))).toMatchObject({ r: 80, cancelled_at: null });
      expect((await q1<any>('SELECT status FROM orders WHERE id = $1', [p.order]))!.status).toBe('novo');
    });

    it('F2: o push de pagamento em dobro orienta "Devolver valor"', async () => {
      await promoted({ payment: 'pago' });
      expect(notifyPaid).toHaveBeenCalledWith(owner, expect.objectContaining({ customer: expect.stringContaining('use "Devolver valor" para devolver o Pix') }));
    });

    it('F3: link antigo entregue: a janela de devolução conta do pagamento antigo, não de agora', async () => {
      fakePix();
      const s = await seedLink(80);
      await L.payLinkPix(s.token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [s.link]))!.mp_payment_id as string;
      await q(`UPDATE order_pay_links SET paid_at = now() - interval '10 days', paid_mp_id = $2 WHERE id = $1`, [s.link, mp]);
      await q(`UPDATE orders SET payment = 'pago', status = 'entregue' WHERE id = $1`, [s.order]);
      expect(await L.promoteLink(s.link)).toBe(true);
      const r = (await q1<any>(`SELECT so.delivered_at = l.paid_at AS same, so.delivered_at IS NOT NULL AS has FROM order_pay_links l JOIN shop_orders so ON so.id = l.shop_order_id WHERE l.id = $1`, [s.link]))!;
      expect(r).toMatchObject({ has: true, same: true });
    });

    it('F4: pedido com shop_order_id: PATCH de cancelar é 409 e o status não muda', async () => {
      const p = await promoted();
      const R = await import('@/app/api/orders/[id]/route');
      const res = await R.PATCH(new Request('http://x/api', { method: 'PATCH', body: JSON.stringify({ status: 'cancelado' }) }), { params: Promise.resolve({ id: String(p.order) }) } as any);
      expect(res.status).toBe(409);
      expect((await q1<any>('SELECT status FROM orders WHERE id = $1', [p.order]))!.status).toBe('novo');
    });

    it('F5: editar data e cliente do pedido manual promovido chega ao pedido do cliente', async () => {
      const p = await promoted();
      const R = await import('@/app/api/orders/[id]/route');
      const body = { customer: 'Ana Maria', product: 'Bolo de cenoura', qty: 2, unit_price: 40, status: 'novo', payment: 'pago', delivery_date: '2026-12-24', order_date: '2026-10-01' };
      const res = await R.PATCH(new Request('http://x/api', { method: 'PATCH', body: JSON.stringify(body) }), { params: Promise.resolve({ id: String(p.order) }) } as any);
      expect(res.status).toBe(200);
      expect((await q1<any>(`SELECT to_char(scheduled_for, 'YYYY-MM-DD') AS d, contact_name FROM shop_orders WHERE id = $1`, [p.so]))).toEqual({ d: '2026-12-24', contact_name: 'Ana Maria' });
    });

    it('F7: readRefundForm recusa envio acima de 12 MB sem ler o corpo', async () => {
      const { readRefundForm } = await import('@/lib/refund-form');
      const formData = vi.fn();
      const fake = { headers: new Headers({ 'content-length': '20000000' }), formData } as unknown as Request;
      expect(await readRefundForm(fake)).toMatchObject({ ok: false, status: 413 });
      expect(formData).not.toHaveBeenCalled();
    });

    it('F8: getOrders do pedido promovido traz shop_order_id, shop_code, shop_paid e shop_total', async () => {
      const p = await promoted({ amount: 50 });
      const { getOrders } = await import('@/lib/queries');
      const row: any = (await getOrders(owner)).find((o: any) => o.id === p.order);
      expect(row).toMatchObject({ shop_order_id: p.so, shop_paid: true, shop_total: 50 });
      expect(row.shop_code).toBeGreaterThanOrEqual(1000);
    });

    it('F8: "Gerar novo Pix" com o Pix vencido já aprovado promove o pedido', async () => {
      fakePix();
      const { order, token, link } = await seedLink(80);
      await L.payLinkPix(token);
      const oldId = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_id;
      await q(`UPDATE order_pay_links SET pix_expires_at = now() - interval '1 minute' WHERE id = $1`, [link]);
      getPayment.mockResolvedValue({ id: Number(oldId), status: 'approved', transaction_amount: 80 });
      expect(await L.payLinkPix(token)).toEqual({ ok: true, state: 'paid' });
      expect((await q1<any>('SELECT shop_order_id FROM orders WHERE id = $1', [order]))!.shop_order_id).toBeTruthy();
    });
  });
  describe('N1/N2', () => {
    async function promoted() {
      fakePix();
      const s = await seedLink(80);
      await L.payLinkPix(s.token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [s.link]))!.mp_payment_id as string;
      await L.markLinkPaid(s.link, mp, 80);
      const so = (await q1<any>('SELECT shop_order_id FROM orders WHERE id = $1', [s.order]))!.shop_order_id as number;
      return { ...s, so };
    }
    const patch = async (order: number, body: unknown) => {
      const R = await import('@/app/api/orders/[id]/route');
      return R.PATCH(new Request('http://x/api', { method: 'PATCH', body: JSON.stringify(body) }), { params: Promise.resolve({ id: String(order) }) } as any);
    };

    it('N1: pedido promovido aceita troca rápida só de pagamento e de status; cancelar é 409', async () => {
      const p = await promoted();
      expect((await patch(p.order, { payment: 'sinal' })).status).toBe(200);
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [p.order]))!.payment).toBe('sinal');
      expect((await patch(p.order, { status: 'producao' })).status).toBe(200);
      const res = await patch(p.order, { status: 'cancelado' });
      expect(res.status).toBe(409);
      expect((await res.json()).error).toContain('Cancelar pedido');
      expect((await q1<any>('SELECT status FROM orders WHERE id = $1', [p.order]))!.status).toBe('producao');
    });

    it('N2: pedido manual 100% devolvido por "Devolver valor" ainda pode ser cancelado, sem novo estorno', async () => {
      const p = await promoted();
      createRefund.mockResolvedValue({ id: 72, status: 'approved', amount: 80 });
      const { ownerRefund, ownerCancel } = await import('@/lib/refunds');
      expect(await ownerRefund(p.so, owner, '80', 'Só sinal, não vai acontecer')).toMatchObject({ ok: true });
      const count = async () => Number((await q1<any>('SELECT count(*) AS n FROM refunds WHERE shop_order_id = $1', [p.so]))!.n);
      expect(await count()).toBe(1);
      expect(await ownerCancel(p.so, owner, 'outro', 'Pedido não vai acontecer')).toMatchObject({ ok: true, refundId: null, refunded: 0 });
      expect((await q1<any>('SELECT cancelled_at, cancelled_by FROM shop_orders WHERE id = $1', [p.so]))).toMatchObject({ cancelled_by: 'loja' });
      expect((await q1<any>('SELECT status FROM orders WHERE id = $1', [p.order]))!.status).toBe('cancelado');
      expect(await count()).toBe(1);
      expect(createRefund).toHaveBeenCalledTimes(1);
      expect(await ownerCancel(p.so, owner, 'outro', 'de novo')).toMatchObject({ ok: false, status: 409 });
    });

    it('N2: o cliente cancela pelo link o pedido manual já 100% devolvido sem devolver de novo', async () => {
      const p = await promoted();
      createRefund.mockResolvedValue({ id: 73, status: 'approved', amount: 80 });
      const { ownerRefund } = await import('@/lib/refunds');
      await ownerRefund(p.so, owner, '80', 'Pix em dobro');
      const C = await import('@/app/api/pagar/[token]/cancelar/route');
      const res = await C.POST(new Request('http://x/api', { method: 'POST', body: JSON.stringify({ reason_code: 'nao_preciso', reason: '' }) }), { params: Promise.resolve({ token: p.token }) } as any);
      expect(res.status).toBe(200);
      expect(createRefund).toHaveBeenCalledTimes(1);
      expect(Number((await q1<any>('SELECT count(*) AS n FROM refunds WHERE shop_order_id = $1', [p.so]))!.n)).toBe(1);
    });
  });
});
