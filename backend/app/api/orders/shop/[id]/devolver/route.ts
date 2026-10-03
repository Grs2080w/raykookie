import { fail, ok, route } from '@/lib/api';
import { ownerRefund } from '@/lib/refunds';

/** Devolve um valor ao cliente sem cancelar o pedido (ex.: faltou um item). */
export const POST = route(async (req, user, { id }) => {
  const b = await req.json().catch(() => ({}));
  const r = await ownerRefund(Number(id), user.id, b.amount, b.note);
  return r.ok ? ok(r) : fail(r.error, r.status);
});
