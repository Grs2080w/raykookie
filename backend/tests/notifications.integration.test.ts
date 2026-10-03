// Notificações do processo de pedido, cancelamento e devolução, contra um Postgres de verdade (TEST_DATABASE_URL).
// Só o Mercado Pago é simulado; o push é capturado no lugar do Firebase (setPushSender), com o texto que iria ao celular.
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';

const TEST_DB = process.env.TEST_DATABASE_URL;
const d = TEST_DB ? describe : describe.skip;
if (TEST_DB) process.env.DATABASE_URL = TEST_DB;

const createRefund = vi.fn();
vi.mock('@/lib/mercadopago', async (orig) => ({ ...(await orig<any>()), createRefund: (...a: unknown[]) => createRefund(...a), getPayment: vi.fn(), cancelPayment: vi.fn(), getRefunds: vi.fn(async () => []) }));

type Sent = { token: string; title: string; body: string; type: string; orderId?: string; refundId?: string; channel: string };

d('notificações (banco real)', () => {
  let q: typeof import('@/lib/db').q, q1: typeof import('@/lib/db').q1, pool: typeof import('@/lib/db').pool;
  let R: typeof import('@/lib/refunds'), P: typeof import('@/lib/push'), S: typeof import('@/lib/order-sync'), C: typeof import('@/lib/cancel'), SQ: typeof import('@/lib/shop-queries');
  let owner = 0, customer = 0, n = 0;
  const sent: Sent[] = [];
  const OWNER_TOKEN = `owner-device-${Date.now()}-xxxxxxxxxxxx`, CUST_TOKEN = `customer-device-${Date.now()}-xxxxxxxxx`;
  const flush = () => new Promise((r) => setTimeout(r, 60)); // os avisos saem sem await
  const toOwner = () => sent.filter((m) => m.token === OWNER_TOKEN);
  const toCustomer = () => sent.filter((m) => m.token === CUST_TOKEN);

  async function seed(status: string, o: { paid?: boolean; delivered?: boolean } = {}) {
    n++;
    const paid = o.paid ?? true;
    const so = await q1<{ id: number; code: number }>(
      `INSERT INTO shop_orders(customer_id, owner_id, contact_name, phone, fulfillment, scheduled_for, time_window, payment_method, items, total, public_code, paid_at, paid_mp_id, mp_payment_ids, delivered_at)
       VALUES ($1,$2,'Maria','11999999999','retirada','2026-10-20','tarde','pix',$3,100,$4,$5,$6,$7,$8) RETURNING id, public_code AS code`,
      [customer, owner, JSON.stringify([{ id: '1', name: 'Brownie', qty: 2, unit_price: 30 }, { id: '2', name: 'Torta', qty: 1, unit_price: 40 }]), 10000 + Math.floor(Math.random() * 80000),
        paid ? new Date() : null, paid ? `pay${n}${Date.now()}` : null, paid ? [`pay${n}${Date.now()}`] : [], o.delivered ? new Date() : null]);
    for (const [i, name] of ['Brownie', 'Torta'].entries()) {
      await q(`INSERT INTO orders(user_id, number, customer, product, qty, unit_price, status, payment, shop_order_id) VALUES ($1,(SELECT COALESCE(MAX(number),0)+1 FROM orders WHERE user_id=$1),'Maria',$2,1,10,$3,$4,$5)`,
        [owner, name, status, paid ? 'pago' : 'pendente', so!.id]);
    }
    return so!;
  }

  beforeEach(async () => {
    const db = await import('@/lib/db'); q = db.q; q1 = db.q1; pool = db.pool;
    R = await import('@/lib/refunds'); P = await import('@/lib/push'); S = await import('@/lib/order-sync'); C = await import('@/lib/cancel'); SQ = await import('@/lib/shop-queries');
    P.setPushSender(async (msgs) => {
      for (const m of msgs) sent.push({ token: m.token, title: m.notification.title, body: m.notification.body, type: m.data.type, orderId: m.data.orderId, refundId: m.data.refundId, channel: m.android.notification.channelId });
      return msgs.map((m) => ({ token: m.token, ok: true, dead: false }));
    });
    sent.length = 0;
    createRefund.mockReset(); createRefund.mockResolvedValue({ id: 1, status: 'approved' });
    if (!owner) {
      owner = (await q1<any>(`INSERT INTO users(username, password_hash) VALUES ($1,'x') RETURNING id`, [`notif-${Date.now()}`]))!.id;
      customer = (await q1<any>(`INSERT INTO customers(email, name) VALUES ($1,'Maria') RETURNING id`, [`n${Date.now()}@x.com`]))!.id;
      await q(`INSERT INTO device_tokens(user_id, token) VALUES ($1,$2)`, [owner, OWNER_TOKEN]);
      await q(`INSERT INTO customer_devices(customer_id, token) VALUES ($1,$2)`, [customer, CUST_TOKEN]);
    }
  });
  afterAll(async () => { P?.setPushSender(undefined); await pool?.end(); });

  it('cliente cancela pedido pago: a confeiteira é avisada (com o Pix devolvido) e a cliente recebe "Pix devolvido"', async () => {
    const o = await seed('novo');
    expect(await C.cancelShopOrder(o.id, customer, '', 'data_horario')).toMatchObject({ ok: true, refunded: 100 });
    await flush();
    expect(toOwner()).toEqual([expect.objectContaining({ type: 'pedido_cancelado', title: `Pedido #${o.code} cancelado pela cliente`, body: expect.stringContaining('O Pix de R$ 100,00 foi devolvido automaticamente') })]);
    expect(toOwner()[0].body).toContain('A data ou o horário não servem mais');
    expect(toCustomer()).toEqual([expect.objectContaining({ type: 'devolucao', title: `Pix devolvido · #${o.code}`, body: 'R$ 100,00 voltaram para a conta que pagou.', orderId: String(o.id), channel: 'pedidos_cliente' })]);
  });

  it('loja cancela com motivo e mensagem: a cliente recebe o motivo e vê tudo no pedido', async () => {
    const o = await seed('producao');
    expect(await R.ownerCancel(o.id, owner, 'falta_ingrediente', 'O chocolate acabou no fornecedor.')).toMatchObject({ ok: true, refunded: 100 });
    await flush();
    const c = toCustomer();
    expect(c[0]).toMatchObject({ type: 'cancelado', title: `Pedido cancelado pela loja · #${o.code}`, body: 'Motivo: Faltou ingrediente. R$ 100,00 voltam por Pix.' });
    expect(c[1]).toMatchObject({ title: `Pix devolvido · #${o.code}` });
    expect(toOwner()).toHaveLength(0); // ela mesma cancelou: sem aviso para si
    const mine = (await SQ.getCustomerOrders(customer)).find((x) => x.id === o.id)!;
    expect(mine.cancel).toMatchObject({ by: 'loja', reason: 'Faltou ingrediente', note: 'O chocolate acabou no fornecedor.' });
    expect(mine.refunds[0]).toMatchObject({ kind: 'loja', status: 'concluido', refunded_at: expect.any(String) });
  });

  it('pedido de devolução: a confeiteira recebe motivo, valor e nº de fotos; desistir também avisa', async () => {
    const o = await seed('producao');
    const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(30)]);
    const r = await R.requestRefund(o.id, customer, { reason_code: 'avariado', description: 'Chegou amassado e derretido, veja as fotos.', items: [{ id: '1', qty: 1 }], photos: [{ type: 'image/png', data: png }, { type: 'image/png', data: png }] });
    expect(r.ok).toBe(true);
    await flush();
    expect(toOwner()[0]).toMatchObject({ type: 'devolucao', title: `Pedido de devolução #${o.code}`, body: 'Maria pede R$ 30,00 de volta: chegou amassado, quebrado ou derretido. 2 fotos. Toque para decidir.', refundId: String((r as any).refundId) });
    sent.length = 0;
    expect(await R.withdrawRefund(o.id, customer)).toEqual({ ok: true });
    await flush();
    expect(toOwner()[0]).toMatchObject({ title: `Cliente desistiu da devolução #${o.code}` });
  });

  it('aprovação parcial: a cliente recebe "aprovada (R$ x de R$ y)" e depois "Pix devolvido"', async () => {
    const o = await seed('entregue', { delivered: true });
    const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(30)]);
    const r: any = await R.requestRefund(o.id, customer, { reason_code: 'qualidade', description: 'O brownie veio seco e duro, não deu para comer.', items: [{ id: '1', qty: 2 }], photos: [{ type: 'image/png', data: png }] });
    sent.length = 0;
    expect(await R.decideRefund(r.refundId, owner, { approve: true, amount: '20', note: 'Devolvemos parte' })).toMatchObject({ ok: true, status: 'aprovado' });
    await flush();
    expect(toCustomer().map((m) => [m.title, m.body])).toEqual([
      [`Devolução aprovada · #${o.code}`, 'R$ 20,00 de R$ 60,00. Veja a mensagem da loja.'],
      [`Pix devolvido · #${o.code}`, 'R$ 20,00 voltaram para a conta que pagou.'],
    ]);
  });

  it('negar: a cliente recebe o motivo da lista', async () => {
    const o = await seed('pronto');
    const r: any = await R.requestRefund(o.id, customer, { reason_code: 'desisti', description: 'Não vou mais precisar do pedido, desculpe.', items: [{ id: '2', qty: 1 }], photos: [] });
    sent.length = 0;
    await R.decideRefund(r.refundId, owner, { approve: false, code: 'desistencia_producao', note: '' });
    await flush();
    expect(toCustomer()).toEqual([expect.objectContaining({ title: `Devolução negada · #${o.code}`, body: 'Motivo: Desistência com o pedido já produzido.' })]);
  });

  it('mudança de etapa avisa a cliente uma vez por etapa (em preparo, pronto, entregue)', async () => {
    const o = await seed('novo');
    const items = await q<{ id: number }>('SELECT id FROM orders WHERE shop_order_id = $1 ORDER BY id', [o.id]);
    const step = async (id: number, status: string) => { const before = await S.shopSnapshot(id, owner); await q('UPDATE orders SET status = $2 WHERE id = $1', [id, status]); await S.afterStatusChange(before); };
    await step(items[0].id, 'producao'); // o outro item ainda é "novo": a etapa geral continua "Recebido"
    await flush();
    expect(toCustomer()).toHaveLength(0);
    await step(items[1].id, 'producao');
    await step(items[0].id, 'pronto'); await step(items[1].id, 'pronto');
    await step(items[0].id, 'entregue'); await step(items[1].id, 'entregue');
    await flush();
    expect(toCustomer().map((m) => m.title)).toEqual([`Pedido em preparo · #${o.code}`, `Pedido pronto · #${o.code}`, `Pedido entregue · #${o.code}`]);
    expect(toCustomer()[1].body).toBe('Pode retirar no endereço combinado, no período da tarde.');
    expect(toCustomer()[2].body).toContain('pedir devolução em até 48h');
    expect((await q1<any>('SELECT delivered_at FROM shop_orders WHERE id = $1', [o.id]))!.delivered_at).not.toBeNull();
  });

  it('devolução que falhou e depois deu certo: a confeiteira recebe "concluída"', async () => {
    const o = await seed('novo');
    const { MpError } = await import('@/lib/mercadopago');
    createRefund.mockRejectedValueOnce(new MpError(428, 'insufficient_money'));
    await R.cancelAndRefund(o.id, customer, 'preco', '');
    const rf = await q1<any>('SELECT id FROM refunds WHERE shop_order_id = $1', [o.id]);
    await flush();
    expect(toOwner().some((m) => m.title === 'Devolução parada: falta saldo')).toBe(true);
    sent.length = 0;
    expect(await R.retryRefund(rf.id, owner)).toMatchObject({ status: 'concluido' });
    await flush();
    expect(toOwner()).toEqual([expect.objectContaining({ title: `Devolução concluída #${o.code}` })]);
    expect(toCustomer()).toEqual([expect.objectContaining({ title: `Pix devolvido · #${o.code}` })]);
  });

  it('excluir a conta apaga os aparelhos: nenhum aviso chega mais', async () => {
    const c2 = (await q1<any>(`INSERT INTO customers(email, name) VALUES ($1,'Bia') RETURNING id`, [`b${Date.now()}@x.com`]))!.id;
    await q(`INSERT INTO customer_devices(customer_id, token) VALUES ($1,$2)`, [c2, `bia-device-${Date.now()}-xxxxxxxxxx`]);
    const { deleteAccount } = await import('@/lib/customer-privacy');
    expect(await deleteAccount(c2, 'EXCLUIR', '')).toEqual({ ok: true });
    expect((await q1<any>('SELECT count(*)::int AS n FROM customer_devices WHERE customer_id = $1', [c2]))!.n).toBe(0);
  });
});
