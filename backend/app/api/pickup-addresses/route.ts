import { fail, ok, route } from '@/lib/api';
import { q1, tx } from '@/lib/db';
import { listPickup, MAX_PICKUP, parsePickup, withAddress, type PickupAddress } from '@/lib/pickup';

export const GET = route(async (_req, user) => ok(await listPickup(user.id)));

// Novo endereço de retirada. O primeiro vira o ativo; os outros só se `active` vier verdadeiro.
export const POST = route(async (req, user) => {
  const b = await req.json().catch(() => ({}));
  const p = parsePickup(b);
  if ('error' in p) return fail(p.error);
  const count = (await q1<{ n: number }>('SELECT count(*)::int AS n FROM pickup_addresses WHERE user_id = $1', [user.id]))?.n ?? 0;
  if (count >= MAX_PICKUP) return fail(`Você pode guardar até ${MAX_PICKUP} endereços. Exclua algum para adicionar outro.`);
  const v = p.value;
  const row = await tx(async (c) => {
    const makeActive = count === 0 || b?.active === true;
    if (makeActive) await c.query('UPDATE pickup_addresses SET is_active = false WHERE user_id = $1 AND is_active', [user.id]);
    const { rows } = await c.query(
      `INSERT INTO pickup_addresses(user_id, label, cep, street, number, complement, district, city, instructions, is_active)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING id, label, cep, street, number, complement, district, city, instructions, is_active`,
      [user.id, v.label, v.cep, v.street, v.number, v.complement, v.district, v.city, v.instructions, makeActive],
    );
    return rows[0] as PickupAddress;
  });
  return ok(withAddress(row), 201);
});
