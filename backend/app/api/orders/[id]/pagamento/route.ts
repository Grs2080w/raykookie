import { fail, num, ok, route } from '@/lib/api';
import { ensurePayLink } from '@/lib/pay-link';

// Link de pagamento Pix de um pedido registrado à mão. `amount` (opcional, texto pt-BR ou número) é o valor a cobrar: o padrão é o total.
export const POST = route(async (req, user, { id }) => {
  const orderId = Number(id);
  if (!Number.isInteger(orderId)) return fail('Pedido não encontrado.', 404);
  const b = await req.json().catch(() => ({}));
  const amount = b?.amount == null || String(b.amount).trim() === '' ? null : num(b.amount);
  const r = await ensurePayLink(user.id, orderId, amount);
  return r.ok ? ok({ url: r.url, amount: r.amount }) : fail(r.error, r.status);
});
