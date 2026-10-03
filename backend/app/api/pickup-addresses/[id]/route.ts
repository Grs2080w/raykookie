import { fail, ok, route } from '@/lib/api';
import { q, q1, tx } from '@/lib/db';
import { parsePickup, withAddress, type PickupAddress } from '@/lib/pickup';

const COLS = 'id, label, cep, street, number, complement, district, city, instructions, is_active';

// Edita o endereço e/ou o escolhe como o de retirada (`active: true`). Só um fica ativo.
export const PATCH = route(async (req, user, { id }) => {
  const b = await req.json().catch(() => ({}));
  const exists = await q1('SELECT 1 FROM pickup_addresses WHERE id = $1 AND user_id = $2', [id, user.id]);
  if (!exists) return fail('Endereço não encontrado.', 404);
  const editing = ['label', 'street', 'number', 'district', 'city', 'cep', 'complement', 'instructions'].some((k) => k in b);
  let value: ReturnType<typeof parsePickup> | null = null;
  if (editing) {
    value = parsePickup(b);
    if ('error' in value) return fail(value.error);
  }
  const row = await tx(async (c) => {
    if (b?.active === true) {
      await c.query('UPDATE pickup_addresses SET is_active = false WHERE user_id = $1 AND is_active AND id <> $2', [user.id, id]);
      await c.query('UPDATE pickup_addresses SET is_active = true WHERE id = $1 AND user_id = $2', [id, user.id]);
    }
    if (value && 'value' in value) {
      const v = value.value;
      await c.query(
        `UPDATE pickup_addresses SET label=$3, cep=$4, street=$5, number=$6, complement=$7, district=$8, city=$9, instructions=$10 WHERE id=$1 AND user_id=$2`,
        [id, user.id, v.label, v.cep, v.street, v.number, v.complement, v.district, v.city, v.instructions],
      );
    }
    return (await c.query(`SELECT ${COLS} FROM pickup_addresses WHERE id = $1`, [id])).rows[0] as PickupAddress;
  });
  return ok(withAddress(row));
});

// Exclui um endereço. O que está em uso (ativo) só sai se for o único; senão, escolha outro antes.
export const DELETE = route(async (_req, user, { id }) => {
  const a = await q1<{ is_active: boolean }>('SELECT is_active FROM pickup_addresses WHERE id = $1 AND user_id = $2', [id, user.id]);
  if (!a) return ok();
  if (a.is_active) {
    const others = (await q1<{ n: number }>('SELECT count(*)::int AS n FROM pickup_addresses WHERE user_id = $1 AND id <> $2', [user.id, id]))?.n ?? 0;
    if (others > 0) return fail('Este é o endereço de retirada atual. Escolha outro como atual antes de excluir este.', 409);
  }
  await q('DELETE FROM pickup_addresses WHERE id = $1 AND user_id = $2', [id, user.id]);
  return ok();
});
