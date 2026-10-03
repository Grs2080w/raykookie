// Devoluções contra um Postgres de verdade (precisa de TEST_DATABASE_URL com as migrations aplicadas; sem ela, é ignorado).
// O Mercado Pago e o push são simulados: o que se confere é o que o banco decide.
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';

const TEST_DB = process.env.TEST_DATABASE_URL;
const d = TEST_DB ? describe : describe.skip;
if (TEST_DB) process.env.DATABASE_URL = TEST_DB;

const createRefund = vi.fn(), getPayment = vi.fn(), cancelPayment = vi.fn(), getRefunds = vi.fn(), push = vi.fn();
vi.mock('@/lib/mercadopago', async (orig) => ({
  ...(await orig<any>()),
  createRefund: (...a: unknown[]) => createRefund(...a), getPayment: (...a: unknown[]) => getPayment(...a),
  cancelPayment: (...a: unknown[]) => cancelPayment(...a), getRefunds: (...a: unknown[]) => getRefunds(...a),
}));
vi.mock('@/lib/push', async (orig) => ({ ...(await orig<any>()), notifyOwnerRefund: (...a: unknown[]) => push(...a), notifyPrivacyRequest: vi.fn() }));

d('devoluções (banco real)', () => {
  let q: typeof import('@/lib/db').q, q1: typeof import('@/lib/db').q1, pool: typeof import('@/lib/db').pool;
  let R: typeof import('@/lib/refunds');
  let owner = 0, customer = 0, n = 0;
  const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(40)]);

  /** pedido de R$ 100 (2 itens de 30 + 1 de 40), pago, com os itens no status indicado */
  async function seed(status: string, o: { paid?: boolean; delivered?: Date | null } = {}) {
    n++;
    const paid = o.paid ?? true;
    const so = await q1<{ id: number }>(
      `INSERT INTO shop_orders(customer_id, owner_id, contact_name, phone, fulfillment, scheduled_for, time_window, payment_method, items, total, public_code, paid_at, paid_mp_id, mp_payment_ids, delivered_at)
       VALUES ($1,$2,'Maria','11999999999','retirada','2026-10-20','tarde','pix',$3,100,$4,$5,$6,$7,$8) RETURNING id`,
      [customer, owner, JSON.stringify([{ id: '1', name: 'Brownie', qty: 2, unit_price: 30 }, { id: '2', name: 'Torta', qty: 1, unit_price: 40 }]), 10000 + Math.floor(Math.random() * 80000) + n,
        paid ? new Date() : null, paid ? `pay${n}` : null, paid ? [`pay${n}`] : [], o.delivered ?? null]);
    for (const [i, name] of ['Brownie', 'Torta'].entries()) {
      await q(`INSERT INTO orders(user_id, number, customer, product, qty, unit_price, status, payment, shop_order_id) VALUES ($1,$2,'Maria',$3,1,10,$4,$5,$6)`,
        [owner, n * 10 + i, name, status, paid ? 'pago' : 'pendente', so!.id]);
    }
    return so!.id;
  }
  const refundOf = (id: number) => q1<any>('SELECT * FROM refunds WHERE shop_order_id = $1 ORDER BY id DESC LIMIT 1', [id]);
  const order = (id: number) => q1<any>('SELECT * FROM shop_orders WHERE id = $1', [id]);
  const events = async (id: number) => (await q('SELECT type FROM order_events WHERE shop_order_id = $1 ORDER BY id', [id])).map((e: any) => e.type);

  beforeEach(async () => {
    const db = await import('@/lib/db'); q = db.q; q1 = db.q1; pool = db.pool;
    R = await import('@/lib/refunds');
    createRefund.mockReset(); getPayment.mockReset(); cancelPayment.mockReset(); getRefunds.mockReset(); push.mockReset();
    createRefund.mockResolvedValue({ id: 900, status: 'approved', amount: 100 });
    if (!owner) {
      owner = (await q1<any>(`INSERT INTO users(username, password_hash) VALUES ($1, 'x') RETURNING id`, [`rayka-${Date.now()}`]))!.id;
      customer = (await q1<any>(`INSERT INTO customers(email, name) VALUES ($1, 'Maria') RETURNING id`, [`m${Date.now()}@x.com`]))!.id;
    }
  });
  afterAll(async () => { await pool?.end(); });

  it('pago e não iniciado: cancela e devolve o total na hora, uma única vez', async () => {
    const id = await seed('novo');
    const r = await R.cancelAndRefund(id, customer, 'errei_pedido', '');
    expect(r).toMatchObject({ ok: true, refunded: 100 });
    expect(createRefund).toHaveBeenCalledTimes(1);
    expect(createRefund).toHaveBeenCalledWith(`pay${n}`, null, expect.stringMatching(/^rayka-refund-\d+$/));
    expect(await order(id)).toMatchObject({ refunded_total: 100, cancelled_by: 'cliente', cancel_reason_code: 'errei_pedido' });
    expect(await refundOf(id)).toMatchObject({ kind: 'automatico', status: 'concluido', approved_amount: 100 });
    expect((await q("SELECT status FROM orders WHERE shop_order_id = $1", [id])).every((o: any) => o.status === 'cancelado')).toBe(true);
    expect(await events(id)).toEqual(expect.arrayContaining(['cancelado', 'devolucao_aprovada', 'devolucao_concluida']));
    // repetir não devolve de novo
    expect(await R.cancelAndRefund(id, customer, 'errei_pedido', '')).toMatchObject({ ok: false, status: 409 });
    expect(createRefund).toHaveBeenCalledTimes(1);
  });

  it('pago mas em produção: o cancelamento automático é recusado (e nada é devolvido)', async () => {
    const id = await seed('producao');
    expect(await R.cancelAndRefund(id, customer, 'preco', '')).toMatchObject({ ok: false, status: 409 });
    expect(createRefund).not.toHaveBeenCalled();
    expect(await order(id)).toMatchObject({ cancelled_at: null, refunded_total: 0 });
  });

  it('pedido de outro cliente não é encontrado', async () => {
    const id = await seed('novo');
    expect(await R.cancelAndRefund(id, customer + 999, 'preco', '')).toMatchObject({ ok: false, status: 404 });
  });

  it('solicitação: valida motivo, descrição, itens e fotos; aprova parcial e devolve só o valor aprovado', async () => {
    const id = await seed('producao');
    const base = { reason_code: 'qualidade', description: 'O brownie chegou seco e duro, não dá para comer.', items: [{ id: '1', qty: 1 }], photos: [{ type: 'image/png', data: png }] };
    expect(await R.requestRefund(id, customer, { ...base, reason_code: 'xxx' })).toMatchObject({ ok: false, status: 400 });
    expect(await R.requestRefund(id, customer, { ...base, description: 'curto' })).toMatchObject({ ok: false, status: 400 });
    expect(await R.requestRefund(id, customer, { ...base, items: [{ id: '1', qty: 5 }] })).toMatchObject({ ok: false, status: 400 });
    expect(await R.requestRefund(id, customer, { ...base, items: [] })).toMatchObject({ ok: false, status: 400 });
    expect(await R.requestRefund(id, customer, { ...base, photos: Array(6).fill(base.photos[0]) })).toMatchObject({ ok: false, status: 400 });
    const r = await R.requestRefund(id, customer, base);
    expect(r).toMatchObject({ ok: true });
    const row = await refundOf(id);
    expect(row).toMatchObject({ kind: 'solicitado', status: 'solicitado', requested_amount: 30, reason_code: 'qualidade' });
    expect((await q1<any>('SELECT count(*)::int AS n FROM refund_photos WHERE refund_id = $1', [row.id]))!.n).toBe(1);
    expect(push).toHaveBeenCalledWith(owner, expect.objectContaining({ kind: 'solicitada', amount: 30 }));
    // uma só solicitação por pedido
    expect(await R.requestRefund(id, customer, base)).toMatchObject({ ok: false, status: 409 });
    // parcial exige explicação; maior que o restante é recusado
    expect(await R.decideRefund(row.id, owner, { approve: true, amount: 10 })).toMatchObject({ ok: false, status: 400 });
    expect(await R.decideRefund(row.id, owner, { approve: true, amount: 150, note: 'x' })).toMatchObject({ ok: false, status: 400 });
    expect(createRefund).not.toHaveBeenCalled();
    createRefund.mockResolvedValue({ id: 901, status: 'approved', amount: 10 });
    expect(await R.decideRefund(row.id, owner, { approve: true, amount: 10, note: 'Devolvemos parte do valor' })).toMatchObject({ ok: true, status: 'aprovado' });
    expect(createRefund).toHaveBeenCalledWith(`pay${n}`, 10, `rayka-refund-${row.id}`);
    expect(await refundOf(id)).toMatchObject({ status: 'concluido', approved_amount: 10 });
    expect(await order(id)).toMatchObject({ refunded_total: 10, cancelled_at: null }); // parcial: o pedido continua
    // decidir de novo: já decidida
    expect(await R.decideRefund(row.id, owner, { approve: false, code: 'fora_prazo' })).toMatchObject({ ok: false, status: 409 });
  });

  it('negar exige um motivo da lista e não chama o Mercado Pago', async () => {
    const id = await seed('pronto');
    const r = await R.requestRefund(id, customer, { reason_code: 'desisti', description: 'Não vou mais precisar do pedido, desculpe.', items: [{ id: '2', qty: 1 }], photos: [] });
    expect(r.ok).toBe(true);
    const row = await refundOf(id);
    expect(await R.decideRefund(row.id, owner, { approve: false, code: 'inventado' })).toMatchObject({ ok: false, status: 400 });
    expect(await R.decideRefund(row.id, owner, { approve: false, code: 'desistencia_producao', note: 'Já estava pronto' })).toMatchObject({ ok: true, status: 'negado' });
    expect(createRefund).not.toHaveBeenCalled();
    expect(await refundOf(id)).toMatchObject({ status: 'negado', decision_code: 'desistencia_producao' });
  });

  it('entregue: fotos obrigatórias; depois de 48h não aceita; desistir libera nova solicitação', async () => {
    const id = await seed('entregue', { delivered: new Date() });
    const base = { reason_code: 'avariado', description: 'Chegou todo amassado e derretido, veja a foto.', items: [{ id: '1', qty: 1 }], photos: [] as any[] };
    expect(await R.requestRefund(id, customer, base)).toMatchObject({ ok: false, status: 400 }); // sem foto
    const ok1 = await R.requestRefund(id, customer, { ...base, photos: [{ type: 'image/png', data: png }] });
    expect(ok1.ok).toBe(true);
    expect(await R.withdrawRefund(id, customer)).toEqual({ ok: true });
    expect((await R.requestRefund(id, customer, { ...base, photos: [{ type: 'image/png', data: png }] })).ok).toBe(true);
    const late = await seed('entregue', { delivered: new Date(Date.now() - 49 * 3600_000) });
    expect(await R.requestRefund(late, customer, { ...base, photos: [{ type: 'image/png', data: png }] })).toMatchObject({ ok: false, status: 409 });
  });

  it('sem saldo: fica "falhou" com nova tentativa marcada, avisa a confeiteira e o botão refaz', async () => {
    const id = await seed('novo');
    const { MpError } = await import('@/lib/mercadopago');
    createRefund.mockRejectedValueOnce(new MpError(428, '{"message":"insufficient_money_for_refund"}'));
    const r = await R.cancelAndRefund(id, customer, 'preco', '');
    expect(r).toMatchObject({ ok: true });
    const f = await refundOf(id);
    expect(f).toMatchObject({ status: 'falhou', fail_reason: 'saldo', attempts: 1 });
    expect(f.next_try_at).not.toBeNull();
    expect(push).toHaveBeenCalledWith(owner, expect.objectContaining({ kind: 'saldo', amount: 100 }));
    expect(await order(id)).toMatchObject({ refunded_total: 0 });
    createRefund.mockResolvedValue({ id: 950, status: 'approved', amount: 100 });
    expect(await R.retryRefund(f.id, owner)).toMatchObject({ ok: true, status: 'concluido' });
    expect(await order(id)).toMatchObject({ refunded_total: 100 });
    expect(createRefund).toHaveBeenLastCalledWith(`pay${n}`, null, `rayka-refund-${f.id}`); // mesma chave: nunca devolve em dobro
  });

  it('job: refaz as devoluções cuja hora de tentar chegou', async () => {
    const id = await seed('novo');
    const { MpError } = await import('@/lib/mercadopago');
    createRefund.mockRejectedValueOnce(new MpError(500, 'boom'));
    await R.cancelAndRefund(id, customer, 'preco', '');
    const f = await refundOf(id);
    expect(f).toMatchObject({ status: 'falhou', fail_reason: 'erro' });
    expect(await R.retryDue()).toBe(0); // ainda não é a hora
    await q('UPDATE refunds SET next_try_at = now() - interval \'1 minute\' WHERE id = $1', [f.id]);
    createRefund.mockResolvedValue({ id: 960, status: 'approved', amount: 100 });
    expect(await R.retryDue()).toBeGreaterThanOrEqual(1);
    expect(await refundOf(id)).toMatchObject({ status: 'concluido' });
  });

  it('cancelamento pela loja: pedido pago devolve tudo; exige motivo da lista', async () => {
    const id = await seed('producao');
    expect(await R.ownerCancel(id, owner, 'inventado', '')).toMatchObject({ ok: false, status: 400 });
    expect(await R.ownerCancel(id, owner, 'outro', '')).toMatchObject({ ok: false, status: 400 });
    expect(await R.ownerCancel(id, owner, 'falta_ingrediente', '')).toMatchObject({ ok: true, refunded: 100 });
    expect(await order(id)).toMatchObject({ cancelled_by: 'loja', refunded_total: 100 });
    expect(await refundOf(id)).toMatchObject({ kind: 'loja', status: 'concluido' });
  });

  it('a loja devolve um valor sem cancelar; não passa do que resta; soma nunca excede o pago', async () => {
    const id = await seed('producao');
    createRefund.mockResolvedValue({ id: 970, status: 'approved', amount: 40 });
    expect(await R.ownerRefund(id, owner, '40', 'Faltou a torta')).toMatchObject({ ok: true, refunded: 40 });
    expect(await order(id)).toMatchObject({ refunded_total: 40, cancelled_at: null });
    expect(await R.ownerRefund(id, owner, '70', 'demais')).toMatchObject({ ok: false, status: 400 });
    createRefund.mockResolvedValue({ id: 971, status: 'approved', amount: 60 });
    expect(await R.ownerRefund(id, owner, '60,00', 'resto')).toMatchObject({ ok: true });
    expect(await order(id)).toMatchObject({ refunded_total: 100, cancelled_by: 'sistema', cancel_reason_code: 'devolucao_total' }); // devolveu tudo: encerrou
    expect(await R.ownerRefund(id, owner, '1', 'mais um')).toMatchObject({ ok: false, status: 409 });
  });

  it('valor digitado no app é lido em pt-BR: "1.000" é mil (não R$ 1) e "12,5" é 12,50', async () => {
    const id = await seed('producao');
    const milhar = await R.ownerRefund(id, owner, '1.000', 'Faltou');
    expect(milhar).toMatchObject({ ok: false, status: 400 });
    expect((milhar as any).error).toContain('passa do que ainda pode');
    expect(await R.ownerRefund(id, owner, '1.000,00', 'Faltou')).toMatchObject({ ok: false, status: 400, error: expect.stringContaining('passa') });
    expect(await R.ownerRefund(id, owner, '12,5', 'Faltou um item')).toMatchObject({ ok: true, refunded: 12.5 });

    const other = await seed('entregue', { delivered: new Date() });
    await R.requestRefund(other, customer, { reason_code: 'avariado', description: 'Chegou todo amassado e derretido.', items: [{ id: '1', qty: 1 }], photos: [{ type: 'image/png', data: png }] });
    const rid = (await refundOf(other)).id;
    expect(await R.decideRefund(rid, owner, { approve: true, amount: '1.000', note: 'Devolvemos parte' })).toMatchObject({ ok: false, status: 400, error: expect.stringContaining('passa') });
  });

  it('caso #38118: devolução total aprovada encerra o pedido; antes e depois, cancelar ou devolver de novo é recusado', async () => {
    const id = await seed('producao');
    const ask = { reason_code: 'desisti', description: 'Não vou mais precisar do pedido, desculpe.', items: [{ id: '1', qty: 2 }, { id: '2', qty: 1 }], photos: [] };
    expect(await R.requestRefund(id, customer, ask)).toMatchObject({ ok: true });
    const rid = (await refundOf(id)).id;
    // com a solicitação aguardando: a loja decide por ela, não cancela nem devolve por fora
    expect(await R.ownerCancel(id, owner, 'falta_ingrediente', '')).toMatchObject({ ok: false, status: 409, error: expect.stringContaining('aguardando') });
    expect(await R.ownerRefund(id, owner, '10', 'por fora')).toMatchObject({ ok: false, status: 409 });
    expect(await R.decideRefund(rid, owner, { approve: true })).toMatchObject({ ok: true, status: 'aprovado' });
    expect(createRefund).toHaveBeenCalledTimes(1);
    expect(await order(id)).toMatchObject({ refunded_total: 100, cancelled_by: 'sistema', cancel_reason_code: 'devolucao_total', cancel_reason: 'Todo o valor foi devolvido' });
    expect((await q('SELECT status FROM orders WHERE shop_order_id = $1', [id])).map((o: any) => o.status)).toEqual(['cancelado', 'cancelado']);
    // depois de encerrado: nada muda, nada é devolvido de novo, o motivo continua o da devolução
    expect(await R.ownerCancel(id, owner, 'falta_ingrediente', 'tentando de novo')).toMatchObject({ ok: false, status: 409 });
    expect(await R.ownerRefund(id, owner, '1', 'mais um')).toMatchObject({ ok: false, status: 409 });
    expect(await R.cancelAndRefund(id, customer, 'preco', '')).toMatchObject({ ok: false, status: 409 });
    expect(createRefund).toHaveBeenCalledTimes(1);
    expect(await order(id)).toMatchObject({ cancelled_by: 'sistema', cancel_note: '' });
    // o cliente vê o pedido encerrado, sem botão de pedir devolução
    const { getCustomerOrders } = await import('@/lib/shop-queries');
    const mine = (await getCustomerOrders(customer)).find((o) => o.id === id)!;
    expect(mine).toMatchObject({ status: 'cancelado', payment_state: 'devolvido', refund: { mode: 'nenhum' }, cancel: { by: 'sistema', reason: 'Todo o valor foi devolvido' } });
  });

  it('pedido entregue e devolvido por inteiro continua "entregue" (não vira cancelado)', async () => {
    const id = await seed('entregue', { delivered: new Date() });
    expect(await R.ownerRefund(id, owner, '100', 'Bolo errado, devolvemos tudo')).toMatchObject({ ok: true });
    expect(await order(id)).toMatchObject({ refunded_total: 100, cancelled_at: null });
    expect((await q('SELECT status FROM orders WHERE shop_order_id = $1', [id])).every((o: any) => o.status === 'entregue')).toBe(true);
  });

  it('faturamento do mês desconta devoluções, sem descontar duas vezes o que foi cancelado', async () => {
    const { getMonthStats } = await import('@/lib/queries');
    const id = await seed('producao');
    await q(`UPDATE orders SET unit_price = CASE product WHEN 'Brownie' THEN 60 ELSE 40 END WHERE shop_order_id = $1`, [id]);
    const month = new Date().toISOString().slice(0, 7) + '-01';
    const before = (await getMonthStats(owner, month)).revenue;
    createRefund.mockResolvedValue({ id: 980, status: 'approved', amount: 40 });
    await R.ownerRefund(id, owner, '40', 'Faltou a torta');
    expect((await getMonthStats(owner, month)).revenue).toBeCloseTo(before - 40);
    await R.ownerRefund(id, owner, '60', 'Devolvemos o resto'); // tudo devolvido: itens cancelados, nada a descontar além deles
    expect((await getMonthStats(owner, month)).revenue).toBeCloseTo(before - 100);
  });

  it('migration 016 corrige pedidos antigos: cancelamento depois da devolução total e pedido devolvido que ficou aberto', async () => {
    const fs = await import('node:fs');
    const sql = fs.readFileSync(new URL('../migrations/016_full_refund_closes.sql', import.meta.url), 'utf8');
    // como ficou o #38118: devolução total concluída e, depois, "cancelado pela loja" sem devolução nova
    const a = await seed('producao');
    await q(`INSERT INTO refunds(shop_order_id, owner_id, customer_id, kind, status, reason_code, requested_amount, approved_amount, decided_at, refunded_at, created_at)
             VALUES ($1,$2,$3,'solicitado','concluido','desisti',100,100, now() - interval '6 minutes', now() - interval '6 minutes', now() - interval '7 minutes')`, [a, owner, customer]);
    await q(`UPDATE orders SET status = 'cancelado' WHERE shop_order_id = $1`, [a]);
    await q(`UPDATE shop_orders SET refunded_total = 100, cancelled_at = now(), cancelled_by = 'loja', cancel_reason_code = 'falta_ingrediente', cancel_reason = 'Faltou ingrediente', cancel_note = 'msg' WHERE id = $1`, [a]);
    // devolvido por inteiro e ainda "em produção"
    const b = await seed('producao');
    await q(`UPDATE shop_orders SET refunded_total = 100 WHERE id = $1`, [b]);
    // cancelado pela loja de verdade (o cancelamento devolveu): não muda
    const c = await seed('producao');
    expect(await R.ownerCancel(c, owner, 'sem_agenda', '')).toMatchObject({ ok: true, refunded: 100 });
    await q(sql);
    expect(await order(a)).toMatchObject({ cancelled_by: 'sistema', cancel_reason_code: 'devolucao_total', cancel_note: '' });
    expect(await order(b)).toMatchObject({ cancelled_by: 'sistema', cancel_reason_code: 'devolucao_total' });
    expect((await q('SELECT status FROM orders WHERE shop_order_id = $1', [b])).every((o: any) => o.status === 'cancelado')).toBe(true);
    expect(await order(c)).toMatchObject({ cancelled_by: 'loja', cancel_reason_code: 'sem_agenda' });
  });

  it('pagamento que chegou depois do cancelamento: devolução automática do sistema', async () => {
    const id = await seed('novo', { paid: false });
    await q(`UPDATE shop_orders SET cancelled_at = now(), cancelled_by = 'cliente', paid_at = now(), paid_mp_id = 'late1', mp_payment_ids = ARRAY['late1'] WHERE id = $1`, [id]);
    const rid = await R.refundLatePayment(id);
    expect(rid).toBeTruthy();
    expect(await refundOf(id)).toMatchObject({ kind: 'sistema', reason_code: 'pago_apos_cancelamento', status: 'concluido' });
    expect(push).toHaveBeenCalledWith(owner, expect.objectContaining({ kind: 'tardio' }));
  });

  it('devolução feita direto no painel do Mercado Pago entra na conciliação', async () => {
    const id = await seed('producao');
    const mpid = Date.now();
    getRefunds.mockResolvedValue([{ id: mpid, status: 'approved', amount: 25 }]);
    await R.reconcileRefunds(id, `pay${n}`);
    await R.reconcileRefunds(id, `pay${n}`); // repetir não soma de novo
    expect(await order(id)).toMatchObject({ refunded_total: 25 });
    expect(await refundOf(id)).toMatchObject({ kind: 'loja', status: 'concluido', mp_refund_id: String(mpid) });
  });

  it('cancelar pedido não pago (cliente) segue sem devolução: não há o que devolver', async () => {
    const id = await seed('novo', { paid: false });
    const r = await R.cancelAndRefund(id, customer, 'preco', '');
    expect(r).toMatchObject({ ok: true, refunded: 0, refundId: null });
    expect(createRefund).not.toHaveBeenCalled();
  });
});
