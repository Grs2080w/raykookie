import { fail, InputError, num, ok, route, str } from '@/lib/api';
import { tx } from '@/lib/db';
import { parseIngredient } from '@/lib/ingredient-input';
import { nameTokens } from '@/lib/nfce';
import { onPriceChange, type PriceState } from '@/lib/price-impact';

type Add = { action: 'add'; ingredient_id: number; add_qty: number; package_cost: number | null; item_name?: string };
type Create = { action: 'create'; name: string; unit: string; package_size: number; package_cost: number; stock_qty: number };

// Lança a nota no estoque de uma vez só: soma nos ingredientes escolhidos e cria os novos que a confeiteira aceitou.
export const POST = route(async (req, user) => {
  const b = await req.json();
  const key = str(b.key).slice(0, 80);
  if (!key) return fail('Nota sem identificação. Leia o QR code de novo.');
  const list: (Add | Create)[] = Array.isArray(b.items) ? b.items : [];
  if (list.length === 0) return fail('Nenhum item para lançar.');
  if (list.length > 300) return fail('Itens demais numa nota só.');

  const changes: { id: number; before: PriceState; after: PriceState }[] = [];
  const result = await tx(async (c) => {
    const dup = await c.query(
      `INSERT INTO receipt_imports(user_id, access_key, store, issued_on, total) VALUES ($1,$2,$3,$4,$5) ON CONFLICT (user_id, access_key) DO NOTHING RETURNING id`,
      [user.id, key, str(b.store).slice(0, 80), /^\d{4}-\d{2}-\d{2}$/.test(str(b.date)) ? str(b.date) : null, b.total == null ? null : Math.max(0, num(b.total))],
    );
    if (dup.rowCount === 0) throw new InputError('Essa nota já foi lançada no estoque.');
    let added = 0, created = 0;
    for (const it of list) {
      if (it?.action === 'add') {
        const qty = num(it.add_qty);
        if (qty <= 0 || qty > 100000) throw new InputError('A quantidade de um dos itens não é válida.');
        const cost = it.package_cost == null ? null : num(it.package_cost);
        const prev = (await c.query('SELECT package_cost, package_size FROM ingredients WHERE id=$1 AND user_id=$2', [Number(it.ingredient_id), user.id])).rows[0];
        const r = await c.query(
          `UPDATE ingredients SET stock_qty = round((stock_qty + $3)::numeric, 3), package_cost = COALESCE($4, package_cost) WHERE id=$1 AND user_id=$2 RETURNING id, package_cost, package_size`,
          [Number(it.ingredient_id), user.id, qty, cost],
        );
        if (r.rowCount && prev) changes.push({ id: Number(it.ingredient_id), before: { cost: prev.package_cost, size: prev.package_size }, after: { cost: r.rows[0].package_cost, size: r.rows[0].package_size } });
        if (r.rowCount === 0) throw new InputError('Um dos ingredientes escolhidos não existe mais. Leia a nota de novo.');
        const aliasKey = nameTokens(str(it.item_name)).join(' ');
        if (aliasKey) await c.query(`INSERT INTO receipt_aliases(user_id, item_key, ingredient_id) VALUES ($1,$2,$3) ON CONFLICT (user_id, item_key) DO UPDATE SET ingredient_id = EXCLUDED.ingredient_id`, [user.id, aliasKey, Number(it.ingredient_id)]);
        added++;
      } else if (it?.action === 'create') {
        const p = parseIngredient({ ...it, min_stock: 0, supplier: str(b.store).slice(0, 80), notes: 'Criado pela leitura de nota fiscal.' });
        if (p.error) throw new InputError(p.error);
        await c.query(
          `INSERT INTO ingredients(user_id, category_id, name, unit, stock_qty, package_size, package_cost, min_stock, supplier, notes) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`,
          [user.id, ...p.value!],
        );
        created++;
      } else throw new InputError('Item da nota em formato desconhecido.');
    }
    await c.query('UPDATE receipt_imports SET added=$3, created=$4 WHERE user_id=$1 AND access_key=$2', [user.id, key, added, created]);
    return { added, created };
  });
  for (const ch of changes) await onPriceChange(user.id, ch.id, ch.before, ch.after, 'nota'); // depois de gravar: histórico e alertas de margem
  return ok(result, 201);
});
