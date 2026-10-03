import { fail, ok, route } from '@/lib/api';
import { decideRefund } from '@/lib/refunds';

/** Aprovar (total ou parcial, com `amount`) ou negar (`approve:false`, `code` e `note`). */
export const POST = route(async (req, user, { id }) => {
  const b = await req.json().catch(() => ({}));
  const r = await decideRefund(Number(id), user.id, { approve: b.approve === true, amount: b.amount, code: b.code, note: b.note });
  return r.ok ? ok(r) : fail(r.error, r.status);
});
