import { fail, ok, route } from '@/lib/api';
import { addQuote } from '@/lib/suppliers';

/** Cotação manual: `{ingredient_id, supplier_id, price, pack_size}`. */
export const POST = route(async (req, user) => {
  const b = await req.json().catch(() => ({}));
  const r = await addQuote(user.id, { ingredient_id: Number(b.ingredient_id), supplier_id: Number(b.supplier_id), price: b.price, pack_size: b.pack_size });
  return r.ok ? ok(r, 201) : fail(r.error, r.status);
});
