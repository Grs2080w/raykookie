// Preços, cotações e alertas de margem contra um Postgres de verdade (TEST_DATABASE_URL); sem ela, é ignorado.
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';

const TEST_DB = process.env.TEST_DATABASE_URL;
const d = TEST_DB ? describe : describe.skip;
if (TEST_DB) process.env.DATABASE_URL = TEST_DB;

const traySearch = vi.fn(), push = vi.fn();
vi.mock('@/lib/scrapers/tray', async (orig) => ({ ...(await orig<any>()), traySearch: (...a: unknown[]) => traySearch(...a), detectScraper: async () => 'tray' }));
vi.mock('@/lib/scrapers/safe-url', async (orig) => ({ ...(await orig<any>()), assertPublicUrl: async (u: string) => new URL(u) }));
vi.mock('@/lib/push', async (orig) => ({ ...(await orig<any>()), notifyPriceAlert: (...a: unknown[]) => push(...a) }));

d('preços e alertas (banco real)', () => {
  let q: typeof import('@/lib/db').q, q1: typeof import('@/lib/db').q1, pool: typeof import('@/lib/db').pool;
  let S: typeof import('@/lib/suppliers'), P: typeof import('@/lib/price-impact'), Q: typeof import('@/lib/queries');
  const uname = `preco-${Date.now()}`;
  let user = 0, ing = 0, recipe = 0, site = 0, shop = 0;

  beforeEach(async () => {
    const db = await import('@/lib/db'); q = db.q; q1 = db.q1; pool = db.pool;
    S = await import('@/lib/suppliers'); P = await import('@/lib/price-impact'); Q = await import('@/lib/queries');
    traySearch.mockReset(); push.mockReset();
    if (!user) {
      user = (await q1<any>(`INSERT INTO users(username, password_hash) VALUES ($1,'x') RETURNING id`, [uname]))!.id;
      await q(`INSERT INTO settings(user_id, hourly_rate, monthly_units, min_margin_pct) VALUES ($1,0,100,50)`, [user]);
      ing = (await q1<any>(`INSERT INTO ingredients(user_id, name, unit, package_size, package_cost) VALUES ($1,'Chocolate','g',1000,32) RETURNING id`, [user]))!.id;
      recipe = (await q1<any>(`INSERT INTO recipes(user_id, name, yield_units, margin_pct) VALUES ($1,'Brownie',10,100) RETURNING id`, [user]))!.id;
      await q(`INSERT INTO recipe_items(recipe_id, ingredient_id, qty) VALUES ($1,$2,500)`, [recipe, ing]);
      site = (await S.saveSupplier(user, null, { name: 'Romafele', kind: 'site', url: 'https://www.romafele.com.br' }) as any).row.id;
      shop = (await S.saveSupplier(user, null, { name: 'Mercado do Zé', kind: 'loja', address: 'Rua A, 10' }) as any).row.id;
    }
  });
  afterAll(async () => { await pool?.end(); });

  it('fornecedor site vira "tray" e só aceita endereço público; loja física não tem site', async () => {
    const s = await q1<any>('SELECT * FROM suppliers WHERE id = $1', [site]);
    expect(s).toMatchObject({ kind: 'site', scraper: 'tray', url: 'https://www.romafele.com.br' });
    expect(await q1<any>('SELECT scraper, url FROM suppliers WHERE id = $1', [shop])).toMatchObject({ scraper: null, url: '' });
    expect(await S.saveSupplier(user, null, { name: 'X', kind: 'site', url: 'http://localhost:10005' })).toMatchObject({ ok: false, status: 400 });
    expect(await S.saveSupplier(user, null, { name: '', kind: 'loja' })).toMatchObject({ ok: false });
  });

  it('busca nos sites, compara por kg e um site com erro não derruba a busca', async () => {
    traySearch.mockResolvedValueOnce([
      { name: 'Chocolate em pó 1kg', url: 'https://r/choc-1kg', price: 38, available: true, size: 1000, unit: 'g' },
      { name: 'Chocolate granulado 400 g', url: 'https://r/gran', price: 12, available: true, size: 400, unit: 'g' },
      { name: 'Chocolate em barra', url: 'https://r/barra', price: 9, available: true, size: null, unit: null },
    ]);
    const r = await S.searchPrices(user, ing, 'chocolate');
    expect(r!.results[0].products.map((p) => p.per_base)).toEqual([0.038, 0.03, null]);
    traySearch.mockRejectedValueOnce(new Error('O site respondeu 503'));
    const r2 = await S.searchPrices(user, ing, 'chocolate');
    expect(r2!.errors[0]).toMatchObject({ supplier: 'Romafele', error: expect.stringContaining('503') });
    expect((await q1<any>('SELECT last_error FROM suppliers WHERE id = $1', [site]))!.last_error).toContain('503');
  });

  it('liga o produto ao ingrediente, aceita cotação manual e ordena pelo mais barato', async () => {
    expect(await S.linkProduct(user, ing, { supplier_id: site, name: 'Chocolate em pó 1kg', url: 'https://r/choc-1kg', price: 38, size: 1000, unit: 'g' })).toMatchObject({ ok: true });
    expect(await S.linkProduct(user, ing, { supplier_id: site, name: 'x', url: 'https://r/x', price: 38, size: 1000, unit: 'ml' })).toMatchObject({ ok: false, status: 400 }); // unidade diferente
    expect(await S.linkProduct(user, ing, { supplier_id: site, name: 'x', url: 'https://r/x', price: 38, size: null })).toMatchObject({ ok: false, status: 400 }); // sem tamanho
    expect(await S.addQuote(user, { ingredient_id: ing, supplier_id: shop, price: '29,90', pack_size: '1000' })).toMatchObject({ ok: true });
    const p = (await S.ingredientPrices(user, ing))!;
    expect(p.quotes.map((x) => x.supplier)).toEqual(['Mercado do Zé', 'Romafele']);
    expect(p.quotes[0]).toMatchObject({ price: 29.9, source: 'manual', kind: 'loja' });
  });

  it('"usar este preço" muda o ingrediente, grava histórico e avisa a margem; sem preço fixado avisa o aumento do cardápio', async () => {
    // sem preço fixado: chocolate R$ 32 → 39,90 por kg faz o preço sugerido subir mais de 5%
    const before = (await Q.getPricedRecipes(user)).find((r) => r.id === recipe)!.pricing.rounded;
    await q('UPDATE ingredients SET package_cost = 39.9 WHERE id = $1', [ing]);
    const n = await P.onPriceChange(user, ing, { cost: 32, size: 1000 }, { cost: 39.9, size: 1000 }, 'edicao');
    expect(n).toBe(1);
    const a = await q1<any>(`SELECT * FROM price_alerts WHERE recipe_id = $1 ORDER BY id DESC LIMIT 1`, [recipe]);
    expect(a).toMatchObject({ kind: 'preco', before_value: before });
    expect(push).toHaveBeenCalledWith(user, expect.objectContaining({ name: 'Brownie', kind: 'preco' }));
    expect((await q1<any>('SELECT price_updated_at FROM ingredients WHERE id = $1', [ing]))!.price_updated_at).not.toBeNull();
    // aplicar o preço sugerido fixa o cardápio nesse valor
    await q('UPDATE recipes SET sale_price = $2 WHERE id = $1', [recipe, a.suggested]);
    expect((await Q.getPricedRecipes(user)).find((r) => r.id === recipe)!.pricing.rounded).toBe(a.suggested);
  });

  it('com preço fixado: o ingrediente sobe de novo e a margem cai abaixo do mínimo (50%)', async () => {
    const sale = (await q1<any>('SELECT sale_price::float8 AS s FROM recipes WHERE id = $1', [recipe]))!.s;
    expect(sale).toBeGreaterThan(0);
    // via cotação: o ingrediente passa a R$ 55/kg
    const quote = await q1<any>(`INSERT INTO price_quotes(user_id, ingredient_id, supplier_id, price, pack_size, pack_unit, source) VALUES ($1,$2,$3,55,1000,'g','manual') RETURNING id`, [user, ing, shop]);
    const r = await S.useQuote(user, ing, quote.id);
    expect(r).toMatchObject({ ok: true, alerts: 1 });
    expect(await q1<any>('SELECT package_cost::float8 AS c FROM ingredients WHERE id = $1', [ing])).toMatchObject({ c: 55 });
    const a = await q1<any>(`SELECT * FROM price_alerts WHERE recipe_id = $1 AND kind = 'margem'`, [recipe]);
    expect(a.after_value).toBeLessThan(a.before_value);
    expect(a.cause).toContain('Chocolate');
    expect(await S.useQuote(user, ing, 999999)).toMatchObject({ ok: false, status: 404 });
    const h = await P.priceHistory(user, ing);
    expect(h.length).toBeGreaterThanOrEqual(2);
  });

  it('o cardápio da loja usa o preço fixado', async () => {
    const owner = process.env.SHOP_OWNER; process.env.SHOP_OWNER = uname;
    const { getMenu } = await import('@/lib/shop-menu');
    const menu = await getMenu();
    process.env.SHOP_OWNER = owner;
    const sale = (await q1<any>('SELECT sale_price::float8 AS s FROM recipes WHERE id = $1', [recipe]))!.s;
    expect(menu.items.find((i) => i.name === 'Brownie')?.price).toBe(sale);
  });

  it('atualização diária: grava cotação nova quando o preço do site muda, sem mexer no ingrediente', async () => {
    traySearch.mockResolvedValue([{ name: 'Chocolate em pó 1kg', url: 'https://r/choc-1kg', price: 41.5, available: true, size: 1000, unit: 'g' }]);
    const before = (await q1<any>('SELECT package_cost::float8 AS c FROM ingredients WHERE id = $1', [ing]))!.c;
    const r = await S.refreshLinkedPrices(user);
    expect(r).toMatchObject({ changed: 1, failed: 0 });
    expect((await q1<any>(`SELECT price::float8 AS p FROM price_quotes WHERE source = 'scraper' ORDER BY id DESC LIMIT 1`))!.p).toBe(41.5);
    expect((await q1<any>('SELECT package_cost::float8 AS c FROM ingredients WHERE id = $1', [ing]))!.c).toBe(before);
    expect(await S.refreshLinkedPrices(user)).toMatchObject({ changed: 0 }); // preço igual: nada novo
    traySearch.mockRejectedValue(new Error('site mudou'));
    expect(await S.refreshLinkedPrices(user)).toMatchObject({ failed: 1 });
    expect((await q1<any>('SELECT last_error FROM suppliers WHERE id = $1', [site]))!.last_error).toContain('site mudou');
  });
});
