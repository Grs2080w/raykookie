// Tela Pedidos x pedidos do app, contra um Postgres de verdade (precisa de TEST_DATABASE_URL; sem ela, é ignorado).
// Linha de pedido do app não some, não muda de item ou valor e, encerrado o pedido, não muda mais de status.
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

const TEST_DB = process.env.TEST_DATABASE_URL;
const d = TEST_DB ? describe : describe.skip;
if (TEST_DB) process.env.DATABASE_URL = TEST_DB;

let owner = 0;
vi.mock('@/lib/auth', () => ({ getUser: async () => ({ id: owner, username: 'rayka' }) }));
vi.mock('@/lib/push', async (orig) => ({ ...(await orig<any>()), notifyCustomerStatus: vi.fn() }));

d('tela Pedidos com pedidos do app (banco real)', () => {
  let q: typeof import('@/lib/db').q, q1: typeof import('@/lib/db').q1, pool: typeof import('@/lib/db').pool;
  let R: typeof import('@/app/api/orders/[id]/route');
  let customer = 0, n = 0;
  const ctx = (id: number) => ({ params: Promise.resolve({ id: String(id) }) }) as any;
  const req = (method: string, body?: unknown) => new Request('http://x/api', { method, body: body == null ? undefined : JSON.stringify(body) });

  /** pedido do app com uma linha na tela Pedidos; devolve o id da linha */
  async function seed(o: { cancelled?: boolean } = {}) {
    n++;
    const so = await q1<{ id: number }>(
      `INSERT INTO shop_orders(customer_id, owner_id, contact_name, phone, fulfillment, scheduled_for, time_window, payment_method, items, total, public_code, paid_at, cancelled_at, cancelled_by)
       VALUES ($1,$2,'Maria','11999999999','retirada','2026-10-20','tarde','pix','[{"id":"1","name":"Cookie","qty":1,"unit_price":6}]',6,$3,now(),$4,$5) RETURNING id`,
      [customer, owner, 20000 + Math.floor(Math.random() * 70000) + n, o.cancelled ? new Date() : null, o.cancelled ? 'sistema' : null]);
    return (await q1<{ id: number }>(
      `INSERT INTO orders(user_id, number, customer, product, qty, unit_price, status, payment, shop_order_id) VALUES ($1,$2,'Maria','Cookie',1,6,$3,'pago',$4) RETURNING id`,
      [owner, n, o.cancelled ? 'cancelado' : 'producao', so!.id]))!.id;
  }

  beforeAll(async () => {
    const db = await import('@/lib/db'); q = db.q; q1 = db.q1; pool = db.pool;
    R = await import('@/app/api/orders/[id]/route');
    owner = (await q1<any>(`INSERT INTO users(username, password_hash) VALUES ($1, 'x') RETURNING id`, [`pedidos-${Date.now()}`]))!.id;
    customer = (await q1<any>(`INSERT INTO customers(email, name) VALUES ($1, 'Maria') RETURNING id`, [`p${Date.now()}@x.com`]))!.id;
  });
  afterAll(async () => { await pool?.end(); });

  it('linha de pedido do app não pode ser excluída; pedido registrado à mão pode', async () => {
    const line = await seed();
    const res = await R.DELETE(req('DELETE'), ctx(line));
    expect(res.status).toBe(409);
    expect((await res.json()).error).toContain('não pode ser excluído');
    expect(await q1('SELECT 1 FROM orders WHERE id = $1', [line])).toBeTruthy();
    const manual = (await q1<any>(`INSERT INTO orders(user_id, number, customer, product, qty, unit_price) VALUES ($1, 999, 'Ana', 'Bolo', 1, 50) RETURNING id`, [owner]))!.id;
    expect((await R.DELETE(req('DELETE'), ctx(manual))).status).toBe(200);
  });

  it('pedido do app encerrado: status travado (não reabre)', async () => {
    const line = await seed({ cancelled: true });
    const res = await R.PATCH(req('PATCH', { status: 'producao' }), ctx(line));
    expect(res.status).toBe(409);
    expect((await q1<any>('SELECT status FROM orders WHERE id = $1', [line]))!.status).toBe('cancelado');
  });

  it('N1: troca rápida só de pagamento ou de status (não cancelado) em linha de pedido do app funciona; cancelar segue 409', async () => {
    const line = await seed();
    const patch = (b: unknown) => R.PATCH(req('PATCH', b), ctx(line));
    expect((await patch({ payment: 'sinal' })).status).toBe(200);
    expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [line]))!.payment).toBe('sinal');
    expect((await patch({ status: 'producao' })).status).toBe(200);
    expect((await patch({ status: 'cancelado' })).status).toBe(409);
    expect((await q1<any>('SELECT status, payment FROM orders WHERE id = $1', [line]))).toMatchObject({ status: 'producao', payment: 'sinal' });
    const manual = (await q1<any>(`INSERT INTO orders(user_id, number, customer, product, qty, unit_price) VALUES ($1, 998, 'Ana', 'Bolo', 1, 50) RETURNING id`, [owner]))!.id;
    expect((await R.PATCH(req('PATCH', { payment: 'pago' }), ctx(manual))).status).toBe(200);
    expect((await R.PATCH(req('PATCH', { status: 'cancelado' }), ctx(manual))).status).toBe(200);
    expect((await R.PATCH(req('PATCH', { payment: 'pago' }), ctx(987654321))).status).toBe(404);
  });

  it('pedido do app em andamento: muda o status, mas não cancela por aqui nem troca item ou valor', async () => {
    const line = await seed();
    expect((await R.PATCH(req('PATCH', { status: 'pronto' }), ctx(line))).status).toBe(200);
    expect((await R.PATCH(req('PATCH', { status: 'cancelado' }), ctx(line))).status).toBe(409);
    const full = { customer: 'Maria', product: 'Cookie', qty: 1, unit_price: 6, status: 'pronto', payment: 'pago', notes: 'buscar às 15h' };
    expect((await R.PATCH(req('PATCH', full), ctx(line))).status).toBe(200);
    expect((await R.PATCH(req('PATCH', { ...full, qty: 3 }), ctx(line))).status).toBe(409);
    expect((await R.PATCH(req('PATCH', { ...full, unit_price: 1 }), ctx(line))).status).toBe(409);
    expect(await q1<any>('SELECT qty::float8 AS qty, unit_price::float8 AS unit_price, notes FROM orders WHERE id = $1', [line])).toMatchObject({ qty: 1, unit_price: 6, notes: 'buscar às 15h' });
  });
});
