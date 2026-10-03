import { fail, ok, route } from '@/lib/api';
import { retryRefund } from '@/lib/refunds';

/** Refaz uma devolução que falhou (por exemplo, depois de colocar saldo no Mercado Pago). */
export const POST = route(async (_req, user, { id }) => {
  const r = await retryRefund(Number(id), user.id);
  return r.ok ? ok(r) : fail(r.error, r.status);
});
