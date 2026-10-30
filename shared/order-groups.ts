import type { Order } from './types';

/**
 * Junta as linhas do mesmo pedido (mesmo `group_id`) numa lista só, na posição da primeira; as demais ficam sozinhas.
 * Dentro do pedido, os itens saem na ordem em que foram lançados.
 */
export function groupOrders(lines: Order[]): Order[][] {
  const out: Order[][] = [];
  const byGroup = new Map<string, Order[]>();
  for (const o of lines) {
    if (!o.group_id) { out.push([o]); continue; }
    const found = byGroup.get(o.group_id);
    if (found) found.push(o);
    else { const first = [o]; byGroup.set(o.group_id, first); out.push(first); }
  }
  for (const g of out) g.sort((a, b) => a.id - b.id);
  return out;
}

/** Total do pedido: soma de quantidade × preço de cada item. */
export const orderTotal = (lines: Order[]): number => lines.reduce((s, o) => s + o.qty * o.unit_price, 0);
