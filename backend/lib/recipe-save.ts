import { num, str } from './api';
import { q, tx } from './db';

export async function saveRecipe(userId: number, id: number | null, b: any) {
  const name = str(b.name);
  if (!name) return { error: 'Dê um nome para a receita.' };
  const yieldUnits = num(b.yield_units, 1);
  if (yieldUnits <= 0) return { error: 'O rendimento precisa ser maior que zero.' };
  const items: { ingredient_id: number; qty: number }[] = (Array.isArray(b.items) ? b.items : [])
    .map((i: any) => ({ ingredient_id: Number(i.ingredient_id), qty: Math.max(0, num(i.qty)) }))
    .filter((i: any) => i.ingredient_id);
  const ids = items.map((i) => i.ingredient_id);
  if (ids.length) {
    const own = await q<{ id: number }>('SELECT id FROM ingredients WHERE user_id=$1 AND id = ANY($2::int[])', [userId, ids]);
    if (own.length !== new Set(ids).size) return { error: 'Algum ingrediente não foi encontrado no seu estoque.' };
  }
  const vals = [b.category_id ? Number(b.category_id) : null, name.slice(0, 80), yieldUnits, Math.max(0, num(b.margin_pct, 100)), Math.max(0, num(b.prep_hours)), str(b.notes).slice(0, 500), Math.max(0, Math.min(num(b.unit_weight_g), 99999999)), b.sale_price === '' || b.sale_price == null ? null : Math.min(num(b.sale_price), 99999999) || null];
  const row = await tx(async (c) => {
    let r;
    if (id) {
      r = (await c.query(
        `UPDATE recipes SET category_id=$3, name=$4, yield_units=$5, margin_pct=$6, prep_hours=$7, notes=$8, unit_weight_g=$9, sale_price=$10 WHERE id=$1 AND user_id=$2 RETURNING *`,
        [id, userId, ...vals])).rows[0];
      if (!r) return null;
      await c.query('DELETE FROM recipe_items WHERE recipe_id=$1', [id]);
    } else {
      r = (await c.query(
        `INSERT INTO recipes(user_id, category_id, name, yield_units, margin_pct, prep_hours, notes, unit_weight_g, sale_price) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING *`,
        [userId, ...vals])).rows[0];
    }
    for (const it of items) await c.query('INSERT INTO recipe_items(recipe_id, ingredient_id, qty) VALUES ($1,$2,$3)', [r.id, it.ingredient_id, it.qty]);
    return r;
  });
  return row ? { row } : { error: 'Receita não encontrada.', status: 404 };
}

