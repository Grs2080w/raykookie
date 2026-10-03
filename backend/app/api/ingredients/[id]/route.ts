import { fail, num, ok, route } from '@/lib/api';
import { q, q1 } from '@/lib/db';
import { parseIngredient } from '@/lib/ingredient-input';
import { onPriceChange } from '@/lib/price-impact';

export const PATCH = route(async (req, user, { id }) => {
  const b = await req.json();
  // atualização rápida só do estoque
  if (Object.keys(b).length === 1 && 'stock_qty' in b) {
    const row = await q1('UPDATE ingredients SET stock_qty=$3 WHERE id=$1 AND user_id=$2 RETURNING *', [id, user.id, Math.max(0, num(b.stock_qty))]);
    return row ? ok(row) : fail('Ingrediente não encontrado.', 404);
  }
  const p = parseIngredient(b);
  if (p.error) return fail(p.error);
  const old = await q1<{ package_cost: number; package_size: number }>('SELECT package_cost, package_size FROM ingredients WHERE id=$1 AND user_id=$2', [id, user.id]);
  const row = await q1(
    `UPDATE ingredients SET category_id=$3, name=$4, unit=$5, stock_qty=$6, package_size=$7, package_cost=$8, min_stock=$9, supplier=$10, notes=$11
     WHERE id=$1 AND user_id=$2 RETURNING *`,
    [id, user.id, ...p.value!],
  );
  if (row && old) await onPriceChange(user.id, Number(id), { cost: old.package_cost, size: old.package_size }, { cost: (row as any).package_cost, size: (row as any).package_size }, 'edicao');
  return row ? ok(row) : fail('Ingrediente não encontrado.', 404);
});

export const DELETE = route(async (_req, user, { id }) => {
  const used = await q1<{ n: number }>(
    `SELECT count(*)::int n FROM recipe_items ri JOIN recipes r ON r.id = ri.recipe_id WHERE ri.ingredient_id=$1 AND r.user_id=$2`, [id, user.id]);
  if (used && used.n > 0) return fail(`Este ingrediente é usado em ${used.n} receita(s). Remova-o das receitas antes de excluir.`, 409);
  await q('DELETE FROM ingredients WHERE id=$1 AND user_id=$2', [id, user.id]);
  return ok();
});
