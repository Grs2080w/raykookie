import { q, q1, tx } from './db';
import type { Customer } from './customer-auth';
import { overallStatus, PAYMENT_METHODS, type ShopOrderInput } from './shop';

export type ShopOrder = {
  id: number; created_at: string; scheduled_for: string; time_window: string; fulfillment: 'retirada' | 'entrega';
  street: string; number: string; complement: string; district: string; city: string; payment_method: string;
  notes: string; total: number; items: { id: string; name: string; qty: number; unit_price: number }[]; status: string;
};

export async function getCustomerOrders(customerId: number): Promise<ShopOrder[]> {
  const rows = await q<any>(
    `SELECT so.id, so.created_at, so.scheduled_for, so.time_window, so.fulfillment, so.street, so.number, so.complement, so.district, so.city,
            so.payment_method, so.notes, so.total, so.items,
            (SELECT array_agg(o.status) FROM orders o WHERE o.shop_order_id = so.id) AS statuses
     FROM shop_orders so WHERE so.customer_id = $1 ORDER BY so.created_at DESC, so.id DESC LIMIT 100`,
    [customerId],
  );
  return rows.map(({ statuses, ...r }) => ({ ...r, created_at: new Date(r.created_at).toISOString(), status: overallStatus(statuses) }));
}

/** Usuário do painel que recebe os pedidos da loja (SHOP_OWNER no .env; padrão "rayka"). */
export async function shopOwnerId(): Promise<number | null> {
  const row = await q1<{ id: number }>('SELECT id FROM users WHERE lower(username) = lower($1)', [process.env.SHOP_OWNER || 'rayka']);
  return row?.id ?? null;
}

const fmtPhone = (d: string) => (d.length === 11 ? `(${d.slice(0, 2)}) ${d.slice(2, 7)}-${d.slice(7)}` : `(${d.slice(0, 2)}) ${d.slice(2, 6)}-${d.slice(6)}`);

/** Grava o pedido da loja e espelha cada item em `orders`, para aparecer na tela Pedidos do painel. */
export async function createShopOrder(customer: Customer, ownerId: number, o: ShopOrderInput): Promise<number> {
  return tx(async (c) => {
    await c.query('SELECT pg_advisory_xact_lock($1)', [ownerId]); // numeração sequencial sem corrida
    const { rows } = await c.query(
      `INSERT INTO shop_orders(customer_id, owner_id, contact_name, phone, fulfillment, cep, street, number, complement, district, city,
         scheduled_for, time_window, payment_method, change_for, notes, items, total)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18) RETURNING id`,
      [customer.id, ownerId, o.contact_name, o.phone, o.fulfillment, o.cep, o.street, o.number, o.complement, o.district, o.city,
        o.scheduled_for, o.time_window, o.payment_method, o.change_for, o.notes, JSON.stringify(o.items), o.total],
    );
    const id = rows[0].id as number;
    const pay = PAYMENT_METHODS.find((p) => p.id === o.payment_method)?.label;
    const where = o.fulfillment === 'entrega'
      ? `Entrega: ${o.street}, ${o.number}${o.complement ? ` (${o.complement})` : ''} - ${o.district}, ${o.city}`
      : 'Retirada no local';
    const change = o.change_for ? ` (troco p/ R$ ${o.change_for.toFixed(2).replace('.', ',')})` : '';
    for (const [i, it] of o.items.entries()) {
      const notes = [`Pedido online #${id}`, `Tel ${fmtPhone(o.phone)}`, where, `Pagamento: ${pay}${change}`, i === 0 && o.notes ? `Obs: ${o.notes}` : '']
        .filter(Boolean).join(' · ').slice(0, 300);
      await c.query(
        `INSERT INTO orders(user_id, number, customer, recipe_id, product, qty, unit_price, delivery_date, status, payment, notes, shop_order_id)
         VALUES ($1, (SELECT COALESCE(MAX(number),0)+1 FROM orders WHERE user_id=$1), $2,$3,$4,$5,$6,$7,'novo','pendente',$8,$9)`,
        [ownerId, o.contact_name, Number(it.id), it.name, it.qty, it.unit_price, o.scheduled_for, notes, id],
      );
    }
    // guarda os dados para preencher o próximo pedido
    await c.query(
      `UPDATE customers SET phone=$2, cep=CASE WHEN $3 = '' THEN cep ELSE $3 END, street=CASE WHEN $3 = '' THEN street ELSE $4 END,
         number=CASE WHEN $3 = '' THEN number ELSE $5 END, complement=CASE WHEN $3 = '' THEN complement ELSE $6 END,
         district=CASE WHEN $3 = '' THEN district ELSE $7 END, city=CASE WHEN $3 = '' THEN city ELSE $8 END WHERE id=$1`,
      [customer.id, o.phone, o.cep, o.street, o.number, o.complement, o.district, o.city],
    );
    return id;
  });
}
