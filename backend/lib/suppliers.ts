// Fornecedores (sites com busca de preços e lojas físicas), cotações e a atualização diária dos preços ligados a ingredientes.
import { q, q1 } from './db';
import { onPriceChange } from './price-impact';
import { detectScraper, traySearch, type ScrapedProduct } from './scrapers/tray';
import { assertPublicUrl, UnsafeUrl } from './scrapers/safe-url';
import { cleanText } from '@shared/text';

type Row = Record<string, any>;
export type Fail = { ok: false; status: number; error: string };
const fail = (status: number, error: string): Fail => ({ ok: false, status, error });

export const listSuppliers = (userId: number) =>
  q('SELECT id, name, kind, url, address, notes, scraper, last_ok_at, last_error FROM suppliers WHERE user_id = $1 ORDER BY lower(name)', [userId]);

export async function saveSupplier(userId: number, id: number | null, b: any): Promise<{ ok: true; row: Row } | Fail> {
  const name = cleanText(b.name).slice(0, 80);
  if (name.length < 2) return fail(400, 'Dê um nome para o fornecedor.');
  const kind = b.kind === 'loja' ? 'loja' : 'site';
  let url = '', scraper: string | null = null;
  if (kind === 'site') {
    try { url = (await assertPublicUrl(cleanText(b.url))).origin; } catch (e) { return fail(400, e instanceof UnsafeUrl ? e.message : 'Endereço inválido.'); }
    scraper = await detectScraper(url);
  }
  const vals = [name, kind, url, cleanText(b.address).slice(0, 200), cleanText(b.notes).slice(0, 300), scraper];
  const row = id
    ? await q1(`UPDATE suppliers SET name=$3, kind=$4, url=$5, address=$6, notes=$7, scraper=$8, last_error='' WHERE id=$1 AND user_id=$2 RETURNING *`, [id, userId, ...vals])
    : await q1(`INSERT INTO suppliers(user_id, name, kind, url, address, notes, scraper) VALUES ($1,$2,$3,$4,$5,$6,$7) RETURNING *`, [userId, ...vals]);
  return row ? { ok: true, row } : fail(404, 'Fornecedor não encontrado.');
}

/** Procura o ingrediente nos sites dos fornecedores. Um site que falha não derruba a busca: vem em `errors`. */
export async function searchPrices(userId: number, ingredientId: number, term?: string) {
  const ing = await q1<Row>('SELECT id, name, unit FROM ingredients WHERE id = $1 AND user_id = $2', [ingredientId, userId]);
  if (!ing) return null;
  const text = cleanText(term || ing.name).slice(0, 80);
  const sites = await q<Row>(`SELECT id, name, url, scraper FROM suppliers WHERE user_id = $1 AND kind = 'site' AND scraper IS NOT NULL ORDER BY lower(name)`, [userId]);
  const results: { supplier_id: number; supplier: string; products: (ScrapedProduct & { per_base: number | null; match_unit: boolean })[] }[] = [];
  const errors: { supplier_id: number; supplier: string; error: string }[] = [];
  for (const s of sites) {
    try {
      const list = await traySearch(s.url, text);
      await q(`UPDATE suppliers SET last_ok_at = now(), last_error = '' WHERE id = $1`, [s.id]);
      results.push({
        supplier_id: s.id, supplier: s.name,
        products: list.slice(0, 30).map((p) => ({ ...p, match_unit: p.unit === ing.unit, per_base: p.size && p.unit === ing.unit ? p.price / p.size : null })),
      });
    } catch (e) {
      const msg = String((e as Error)?.message ?? e).slice(0, 200);
      await q('UPDATE suppliers SET last_error = $2 WHERE id = $1', [s.id, msg]);
      errors.push({ supplier_id: s.id, supplier: s.name, error: msg });
    }
  }
  return { term: text, unit: ing.unit, results, errors };
}

type Link = { supplier_id: number; name: string; url: string; price: number; size?: number | null; unit?: string | null };
/** Liga um produto do site ao ingrediente (passa a ser atualizado todo dia) e grava a cotação. */
export async function linkProduct(userId: number, ingredientId: number, b: Link): Promise<{ ok: true; quote_id: number } | Fail> {
  const ing = await q1<Row>('SELECT id, unit FROM ingredients WHERE id = $1 AND user_id = $2', [ingredientId, userId]);
  const sup = await q1<Row>('SELECT id FROM suppliers WHERE id = $1 AND user_id = $2', [b.supplier_id, userId]);
  if (!ing || !sup) return fail(404, 'Ingrediente ou fornecedor não encontrado.');
  const size = Number(b.size), price = Number(b.price);
  if (!(size > 0)) return fail(400, 'Informe o tamanho da embalagem (por exemplo, 1000 g).');
  if (!(price > 0)) return fail(400, 'Preço inválido.');
  if (b.unit && b.unit !== ing.unit) return fail(400, `A embalagem está em ${b.unit}, mas o ingrediente é medido em ${ing.unit}. Informe o tamanho em ${ing.unit}.`);
  const url = cleanText(b.url).slice(0, 500);
  if (!url) return fail(400, 'Produto sem endereço.');
  const sp = await q1<Row>(
    `INSERT INTO supplier_products(supplier_id, ingredient_id, external_url, name, pack_size, pack_unit, price) VALUES ($1,$2,$3,$4,$5,$6,$7)
     ON CONFLICT (supplier_id, external_url) DO UPDATE SET ingredient_id = EXCLUDED.ingredient_id, name = EXCLUDED.name, pack_size = EXCLUDED.pack_size, pack_unit = EXCLUDED.pack_unit, price = EXCLUDED.price, seen_at = now()
     RETURNING id`, [b.supplier_id, ingredientId, url, cleanText(b.name).slice(0, 200), size, ing.unit, price]);
  const qt = await q1<{ id: number }>(
    `INSERT INTO price_quotes(user_id, ingredient_id, supplier_id, supplier_product_id, price, pack_size, pack_unit, source) VALUES ($1,$2,$3,$4,$5,$6,$7,'scraper') RETURNING id`,
    [userId, ingredientId, b.supplier_id, sp!.id, price, size, ing.unit]);
  return { ok: true, quote_id: qt!.id };
}

/** Cotação manual: loja física ou site sem busca. O tamanho da embalagem é na unidade do ingrediente. */
export async function addQuote(userId: number, b: { ingredient_id: number; supplier_id: number; price: unknown; pack_size: unknown }): Promise<{ ok: true; quote_id: number } | Fail> {
  const ing = await q1<Row>('SELECT id, unit FROM ingredients WHERE id = $1 AND user_id = $2', [b.ingredient_id, userId]);
  const sup = await q1<Row>('SELECT id FROM suppliers WHERE id = $1 AND user_id = $2', [b.supplier_id, userId]);
  if (!ing || !sup) return fail(404, 'Ingrediente ou fornecedor não encontrado.');
  const price = Number(String(b.price).replace(',', '.')), size = Number(String(b.pack_size).replace(',', '.'));
  if (!(price > 0) || !(size > 0)) return fail(400, 'Informe o preço e o tamanho da embalagem.');
  const qt = await q1<{ id: number }>(
    `INSERT INTO price_quotes(user_id, ingredient_id, supplier_id, price, pack_size, pack_unit, source) VALUES ($1,$2,$3,$4,$5,$6,'manual') RETURNING id`,
    [userId, b.ingredient_id, b.supplier_id, price, size, ing.unit]);
  return { ok: true, quote_id: qt!.id };
}

/** Última cotação de cada fornecedor para o ingrediente, da mais barata (por kg/L/un) para a mais cara, e o histórico. */
export async function ingredientPrices(userId: number, ingredientId: number) {
  const ing = await q1<Row>('SELECT id, name, unit, package_cost::float8 AS package_cost, package_size::float8 AS package_size, price_updated_at FROM ingredients WHERE id = $1 AND user_id = $2', [ingredientId, userId]);
  if (!ing) return null;
  const latest = await q<Row>(
    `SELECT DISTINCT ON (pq.supplier_id) pq.id, pq.supplier_id, s.name AS supplier, s.kind, pq.price::float8 AS price, pq.pack_size::float8 AS pack_size, pq.pack_unit, pq.source, pq.at,
            (pq.price / pq.pack_size)::float8 AS per_base, sp.name AS product, sp.external_url AS url
       FROM price_quotes pq JOIN suppliers s ON s.id = pq.supplier_id LEFT JOIN supplier_products sp ON sp.id = pq.supplier_product_id
      WHERE pq.user_id = $1 AND pq.ingredient_id = $2 AND pq.supplier_id IS NOT NULL ORDER BY pq.supplier_id, pq.at DESC, pq.id DESC`, [userId, ingredientId]);
  latest.sort((a, b) => a.per_base - b.per_base);
  const history = await q<Row>(
    `SELECT pq.price::float8 AS price, pq.pack_size::float8 AS pack_size, pq.source, pq.at, s.name AS supplier FROM price_quotes pq LEFT JOIN suppliers s ON s.id = pq.supplier_id
      WHERE pq.user_id = $1 AND pq.ingredient_id = $2 ORDER BY pq.at DESC, pq.id DESC LIMIT 60`, [userId, ingredientId]);
  return { ingredient: ing, current_per_base: ing.package_size > 0 ? ing.package_cost / ing.package_size : 0, quotes: latest, history };
}

/** "Usar este preço": passa o preço e o tamanho da cotação para o ingrediente (e dispara os alertas de margem). */
export async function useQuote(userId: number, ingredientId: number, quoteId: number): Promise<{ ok: true; alerts: number } | Fail> {
  const qt = await q1<Row>('SELECT price::float8 AS price, pack_size::float8 AS pack_size, pack_unit, supplier_id FROM price_quotes WHERE id = $1 AND user_id = $2 AND ingredient_id = $3', [quoteId, userId, ingredientId]);
  const ing = await q1<Row>('SELECT unit, package_cost::float8 AS cost, package_size::float8 AS size FROM ingredients WHERE id = $1 AND user_id = $2', [ingredientId, userId]);
  if (!qt || !ing) return fail(404, 'Cotação não encontrada.');
  if (qt.pack_unit !== ing.unit) return fail(409, `Essa cotação está em ${qt.pack_unit} e o ingrediente em ${ing.unit}.`);
  await q('UPDATE ingredients SET package_cost = $2, package_size = $3 WHERE id = $1', [ingredientId, qt.price, qt.pack_size]);
  const alerts = await onPriceChange(userId, ingredientId, { cost: ing.cost, size: ing.size }, { cost: qt.price, size: qt.pack_size }, 'manual', qt.supplier_id);
  return { ok: true, alerts };
}

/** Atualização diária: relê o preço dos produtos ligados a ingredientes e grava cotação nova quando mudou. Não mexe no preço do ingrediente. */
export async function refreshLinkedPrices(onlyUserId?: number): Promise<{ checked: number; changed: number; failed: number }> {
  const rows = await q<Row>(
    `SELECT sp.id, sp.supplier_id, sp.ingredient_id, sp.external_url, sp.name, sp.pack_size::float8 AS size, sp.pack_unit, sp.price::float8 AS price, s.url AS base, s.user_id
       FROM supplier_products sp JOIN suppliers s ON s.id = sp.supplier_id WHERE sp.ingredient_id IS NOT NULL AND s.scraper = 'tray' AND ($1::int IS NULL OR s.user_id = $1) ORDER BY s.id, sp.id`, [onlyUserId ?? null]);
  let checked = 0, changed = 0, failed = 0;
  const bad = new Set<number>();
  for (const r of rows) {
    if (bad.has(r.supplier_id)) continue;
    try {
      const found = (await traySearch(r.base, r.name)).find((p) => p.url === r.external_url);
      checked++;
      await q(`UPDATE suppliers SET last_ok_at = now(), last_error = '' WHERE id = $1`, [r.supplier_id]);
      if (!found) { await q('UPDATE supplier_products SET available = false, seen_at = now() WHERE id = $1', [r.id]); continue; }
      await q('UPDATE supplier_products SET available = $2, seen_at = now() WHERE id = $1', [r.id, found.available]);
      if (Math.abs(found.price - r.price) >= 0.005) {
        await q('UPDATE supplier_products SET price = $2 WHERE id = $1', [r.id, found.price]);
        await q(`INSERT INTO price_quotes(user_id, ingredient_id, supplier_id, supplier_product_id, price, pack_size, pack_unit, source) VALUES ($1,$2,$3,$4,$5,$6,$7,'scraper')`,
          [r.user_id, r.ingredient_id, r.supplier_id, r.id, found.price, r.size, r.pack_unit]);
        changed++;
      }
    } catch (e) {
      failed++;
      bad.add(r.supplier_id); // o site está com problema: não insiste nos outros produtos dele hoje
      await q('UPDATE suppliers SET last_error = $2 WHERE id = $1', [r.supplier_id, String((e as Error)?.message ?? e).slice(0, 200)]);
    }
  }
  return { checked, changed, failed };
}
