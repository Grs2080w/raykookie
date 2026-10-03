import { fail, ok, route } from '@/lib/api';
import { listSuppliers, saveSupplier } from '@/lib/suppliers';

export const GET = route(async (_req, user) => ok(await listSuppliers(user.id)));
export const POST = route(async (req, user) => {
  const r = await saveSupplier(user.id, null, await req.json().catch(() => ({})));
  return r.ok ? ok(r.row, 201) : fail(r.error, r.status);
});
