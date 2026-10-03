import { fail, ok, route } from '@/lib/api';
import { ownerCancel } from '@/lib/refunds';

/** A confeiteira cancela um pedido da loja (`id` = id do pedido da loja). Se já estava pago, devolve o Pix inteiro. */
export const POST = route(async (req, user, { id }) => {
  const b = await req.json().catch(() => ({}));
  const r = await ownerCancel(Number(id), user.id, b.code, b.note);
  return r.ok ? ok(r) : fail(r.error, r.status);
});
