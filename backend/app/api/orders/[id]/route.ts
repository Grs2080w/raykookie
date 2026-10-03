import { fail, ok, route, str } from '@/lib/api';
import { q, q1 } from '@/lib/db';
import { parseOrder } from '@/lib/order-input';
import { afterStatusChange, shopSnapshot } from '@/lib/order-sync';

const USE_CANCEL = 'Pedido do app: use "Cancelar pedido", que pede o motivo para a cliente e devolve o Pix se já foi pago.';
const CLOSED = 'Este pedido do app já foi encerrado (cancelado ou devolvido) e não muda mais.';

/** Linha que espelha um item de pedido do app (null = pedido registrado à mão, livre para editar). */
async function shopLine(id: string, userId: number) {
  return q1<{ qty: number; unit_price: number; product: string; status: string; closed: boolean }>(
    `SELECT o.qty::float8 AS qty, o.unit_price::float8 AS unit_price, o.product, o.status, (so.cancelled_at IS NOT NULL) AS closed
       FROM orders o JOIN shop_orders so ON so.id = o.shop_order_id WHERE o.id = $1 AND o.user_id = $2`, [id, userId]);
}

export const PATCH = route(async (req, user, { id }) => {
  const b = await req.json();
  const line = await shopLine(id, user.id);
  // pedido do app encerrado: o cliente já viu o cancelamento e o dinheiro já voltou; reabrir deixaria os dois apps em desacordo
  if (line?.closed) return fail(CLOSED, 409);
  // troca rápida de status/pagamento
  if ('status' in b || 'payment' in b) {
    if (Object.keys(b).length <= 2 && !('customer' in b)) {
      // pedido da loja: cancelar só pelo botão "Cancelar pedido", que pede o motivo (a cliente vê) e devolve o Pix se já pago
      if (b.status === 'cancelado' && line) return fail(USE_CANCEL, 409);
      const before = await shopSnapshot(id, user.id);
      const row = await q1(
        `UPDATE orders SET status=COALESCE($3,status), payment=COALESCE($4,payment) WHERE id=$1 AND user_id=$2 AND NOT (shop_order_id IS NOT NULL AND $3::text IS NOT DISTINCT FROM 'cancelado') RETURNING *`, // guarda contra a promoção que comita entre a leitura e a escrita
        [id, user.id, b.status ? str(b.status) : null, b.payment ? str(b.payment) : null],
      );
      if (row) await afterStatusChange(before);
      if (row) return ok(row);
      return (await q1('SELECT 1 FROM orders WHERE id=$1 AND user_id=$2', [id, user.id])) ? fail(USE_CANCEL, 409) : fail('Pedido não encontrado.', 404);
    }
  }
  const p = parseOrder(b);
  if (p.error) return fail(p.error);
  if (line) {
    const [, , , product, qty, unitPrice, , status] = p.value!;
    if (status === 'cancelado' && line.status !== 'cancelado') return fail(USE_CANCEL, 409);
    // o cliente pagou por estes itens e valores: mudar aqui faria a gestão divergir do que ele vê e do que foi cobrado
    if (product !== line.product || Number(qty) !== line.qty || Math.abs(Number(unitPrice) - line.unit_price) > 0.004) {
      return fail('Itens e valores de pedido do app não mudam por aqui. Para devolver parte do valor, use "Devolver valor".', 409);
    }
  }
  const before = await shopSnapshot(id, user.id);
  const row = await q1(
    `UPDATE orders SET order_date=$3, customer=$4, recipe_id=$5, product=$6, qty=$7, unit_price=$8, delivery_date=$9, status=$10, payment=$11, notes=$12
     WHERE id=$1 AND user_id=$2 RETURNING *`,
    [id, user.id, ...p.value!],
  );
  if (row) {
    // pedido registrado à mão e promovido pelo link: data e nome seguem o que a confeiteira edita (itens e valor ficam como foram pagos)
    await q(`UPDATE shop_orders SET scheduled_for = COALESCE($2::date, scheduled_for), contact_name = $3 WHERE id = (SELECT shop_order_id FROM orders WHERE id = $1) AND manual`, [id, p.value![6], p.value![1]]);
    await afterStatusChange(before);
  }
  return row ? ok(row) : fail('Pedido não encontrado.', 404);
});

export const DELETE = route(async (_req, user, { id }) => {
  // pedido do app não some da gestão: o cliente continua vendo o pedido e o histórico do pagamento precisa ficar
  if (await shopLine(id, user.id)) return fail('Pedido do app não pode ser excluído. Use "Cancelar pedido" se ele não vai acontecer.', 409);
  await q('DELETE FROM orders WHERE id=$1 AND user_id=$2', [id, user.id]);
  return ok();
});
