import { q1, tx } from '@/lib/db';
import { notifyCustomerPaid, notifyPaid } from '@/lib/push';
import { getPayment, validSignature } from '@/lib/mercadopago';
import { logEvent } from '@/lib/order-events';
import { reconcileRefunds, refundLatePayment } from '@/lib/refunds';
import { handleLinkPayment, LINK_REF } from '@/lib/pay-link';

// Notificações do Mercado Pago (URL pública, sem sessão). O pedido só vira "pago" aqui, quando o pagamento
// consultado direto na API do Mercado Pago está "approved": criar o Pix não conta.
export async function POST(req: Request) {
  const secret = process.env.MP_WEBHOOK_SECRET;
  if (!secret || !process.env.MP_ACCESS_TOKEN) return Response.json({ error: 'Webhook não configurado' }, { status: 503 });

  const url = new URL(req.url);
  const body = await req.json().catch(() => null);
  const type = String(body?.type ?? url.searchParams.get('type') ?? url.searchParams.get('topic') ?? '');
  const dataId = String(body?.data?.id ?? url.searchParams.get('data.id') ?? url.searchParams.get('id') ?? '');
  if (!validSignature(secret, dataId, req.headers.get('x-signature'), req.headers.get('x-request-id'))) {
    return Response.json({ error: 'Assinatura inválida' }, { status: 401 });
  }
  if (type !== 'payment' || !dataId) return Response.json({ ok: true, ignored: true });

  try {
    const pay = await getPayment(dataId); // nunca confia no corpo da notificação
    // link de pagamento de pedido registrado à mão: tratador próprio (não toca em shop_orders nem em devoluções)
    const ref = String(pay.external_reference ?? '');
    if (ref.startsWith(LINK_REF)) return Response.json(await handleLinkPayment(pay, Number(ref.slice(LINK_REF.length))));
    const orderId = Number(pay.external_reference);
    if (!Number.isInteger(orderId)) return Response.json({ ok: true, status: pay.status });
    // devolução (feita por nós ou direto no painel do Mercado Pago): confere e fecha a nossa
    if (['refunded', 'charged_back'].includes(pay.status) || (pay as any).transaction_amount_refunded > 0) {
      await reconcileRefunds(orderId, String(pay.id));
      if (pay.status === 'charged_back') await logEvent(orderId, 'mercadopago', 'estorno_cartao', { payment: String(pay.id) });
      return Response.json({ ok: true, status: pay.status });
    }
    if (pay.status !== 'approved') return Response.json({ ok: true, status: pay.status });
    const paidNow = await tx(async (c) => {
      // só aceita o pagamento que foi gerado para este pedido e que cobre o total
      const { rowCount } = await c.query(
        `UPDATE shop_orders SET paid_at = now(), paid_mp_id = $2 WHERE id = $1 AND $2 = ANY(mp_payment_ids) AND paid_at IS NULL AND $3::numeric >= total`,
        [orderId, String(pay.id), pay.transaction_amount],
      );
      if (rowCount) {
        await c.query(`UPDATE orders SET payment = 'pago' WHERE shop_order_id = $1`, [orderId]);
        await logEvent(orderId, 'mercadopago', 'pago', { payment: String(pay.id), amount: pay.transaction_amount }, c);
      }
      return !!rowCount;
    });
    if (paidNow && (await q1('SELECT 1 FROM shop_orders WHERE id = $1 AND cancelled_at IS NOT NULL', [orderId]))) {
      await refundLatePayment(orderId); // pagou um pedido já cancelado: devolve tudo sozinho, sem aviso de "novo pagamento"
      return Response.json({ ok: true, refunded: true });
    }
    if (paidNow) { // só na primeira confirmação: reenvios do Mercado Pago não repetem o aviso
      const o = await q1<{ owner_id: number; customer_id: number; code: number; contact_name: string; total: number; n: number }>(
        'SELECT owner_id, customer_id, public_code AS code, contact_name, total, jsonb_array_length(items)::int AS n FROM shop_orders WHERE id = $1', [orderId]);
      if (o) {
        void notifyPaid(o.owner_id, { id: orderId, code: o.code, customer: o.contact_name, total: o.total, items: o.n });
        void notifyCustomerPaid({ id: orderId, code: o.code, customerId: o.customer_id, total: o.total });
      }
    }
    return Response.json({ ok: true });
  } catch (e) {
    console.error('[mercadopago] falha ao processar notificação', dataId, e);
    return Response.json({ error: 'Falha ao processar' }, { status: 500 }); // o Mercado Pago tenta de novo
  }
}
