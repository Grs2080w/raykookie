import { fail, ok, route } from '@/lib/api';
import { q } from '@/lib/db';
import { saveSupplier } from '@/lib/suppliers';

export const PATCH = route(async (req, user, { id }) => {
  const r = await saveSupplier(user.id, Number(id), await req.json().catch(() => ({})));
  return r.ok ? ok(r.row) : fail(r.error, r.status);
});
export const DELETE = route(async (_req, user, { id }) => {
  await q('DELETE FROM suppliers WHERE id = $1 AND user_id = $2', [Number(id), user.id]);
  return ok();
});
