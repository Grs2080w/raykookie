import { fail, ok, route } from '@/lib/api';
import { useQuote } from '@/lib/suppliers';

/** "Usar este preço": `{quote_id}`. */
export const POST = route(async (req, user, { id }) => {
  const b = await req.json().catch(() => ({}));
  const r = await useQuote(user.id, Number(id), Number(b.quote_id));
  return r.ok ? ok(r) : fail(r.error, r.status);
});
