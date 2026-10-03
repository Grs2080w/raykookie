import { fail, ok, route } from '@/lib/api';
import { linkProduct } from '@/lib/suppliers';

/** Liga um produto do site ao ingrediente: `{ingredient_id, supplier_id, name, url, price, size, unit}`. */
export const POST = route(async (req, user) => {
  const b = await req.json().catch(() => ({}));
  const r = await linkProduct(user.id, Number(b.ingredient_id), { supplier_id: Number(b.supplier_id), name: b.name, url: b.url, price: Number(b.price), size: Number(b.size), unit: b.unit });
  return r.ok ? ok(r, 201) : fail(r.error, r.status);
});
