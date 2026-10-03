// Histórico do pedido (auditoria): quem fez o quê e quando. Gravado junto com a mudança, na mesma transação quando há uma.
import { q } from './db';

type Runner = { query: (sql: string, params?: unknown[]) => Promise<unknown> };
export type Actor = 'cliente' | 'loja' | 'sistema' | 'mercadopago';

export async function logEvent(orderId: number, actor: Actor, type: string, data: Record<string, unknown> = {}, c?: Runner): Promise<void> {
  const sql = 'INSERT INTO order_events(shop_order_id, actor, type, data) VALUES ($1,$2,$3,$4)';
  const params = [orderId, actor, type, JSON.stringify(data)];
  if (c) await c.query(sql, params); else await q(sql, params);
}

export const orderEvents = (orderId: number) =>
  q<{ at: string; actor: Actor; type: string; data: Record<string, unknown> }>('SELECT at, actor, type, data FROM order_events WHERE shop_order_id = $1 ORDER BY at, id', [orderId]);
