// Dados fictícios do painel da confeiteira (site e app de gestão), usados pelo mock-api.mjs só para gerar as capturas do README.
// Nada aqui vem do banco real: nomes, preços, estoques e pedidos são inventados. Os preços saem do cálculo de verdade (shared/pricing.ts).
import { calcRecipe, withSalePrice, stockStatus } from '../../shared/pricing.ts';

const day = (n) => new Date(Date.now() + n * 864e5).toISOString().slice(0, 10);
const iso = (n) => new Date(Date.now() + n * 864e5).toISOString();
const monthKey = new Date().toISOString().slice(0, 7) + '-01';

export const owner = { id: 1, username: 'rayka' };

// ── 1. Categorias (as mesmas sementes que o backend cria para toda conta nova)
const cat = (id, kind, name, icon, color, uses) => ({ id, kind, name, icon, color, uses });
const categories = [
  cat(1, 'ingredient', 'Farinhas e amidos', 'wheat', '#d9a441', 2), cat(2, 'ingredient', 'Açúcares e adoçantes', 'candy', '#e0357a', 2),
  cat(3, 'ingredient', 'Laticínios', 'milk', '#5b8def', 4), cat(4, 'ingredient', 'Chocolates e cacau', 'cookie', '#8a5a44', 3),
  cat(5, 'ingredient', 'Ovos', 'egg', '#f0a04b', 1), cat(6, 'ingredient', 'Gorduras', 'droplet', '#e6b422', 1),
  cat(7, 'ingredient', 'Recheios e coberturas', 'cake-slice', '#c2438a', 1), cat(8, 'ingredient', 'Embalagens', 'package', '#5f9d6e', 1),
  cat(20, 'recipe', 'Cookies e biscoitos', 'cookie', '#d9a441', 3), cat(21, 'recipe', 'Brownies', 'cake-slice', '#8a5a44', 2),
  cat(22, 'recipe', 'Bolos', 'cake', '#e0357a', 2), cat(23, 'recipe', 'Tortas', 'pie-chart', '#e5484d', 1), cat(24, 'recipe', 'Brigadeiros e docinhos', 'candy', '#8a5a44', 1),
  cat(40, 'expense', 'Energia', 'zap', '#e6b422', 1), cat(41, 'expense', 'Água', 'droplets', '#5b8def', 1), cat(42, 'expense', 'Gás', 'flame', '#f0a04b', 1),
  cat(43, 'expense', 'Internet', 'wifi', '#7a8899', 1), cat(44, 'expense', 'Marketing', 'megaphone', '#c2438a', 1),
];

// ── 2. Estoque
const ing = (id, category_id, name, unit, stock_qty, package_size, package_cost, min_stock, supplier) =>
  ({ id, category_id, name, unit, stock_qty, package_size, package_cost, min_stock, supplier, notes: '' });
const ingredients = [
  ing(1, 1, 'Farinha de trigo', 'g', 8200, 5000, 24.9, 3000, 'Atacado Central'),
  ing(2, 2, 'Açúcar mascavo', 'g', 2600, 1000, 8.5, 1000, 'Atacado Central'),
  ing(3, 2, 'Açúcar refinado', 'g', 6400, 5000, 21.9, 2000, 'Atacado Central'),
  ing(4, 3, 'Manteiga sem sal', 'g', 1800, 500, 31.9, 1000, 'Laticínios Vale Verde'),
  ing(5, 3, 'Leite condensado', 'g', 800, 395, 6.9, 1200, 'Atacado Central'),
  ing(6, 3, 'Creme de leite', 'g', 2400, 200, 3.8, 800, 'Laticínios Vale Verde'),
  ing(7, 4, 'Chocolate belga 54%', 'g', 400, 1010, 74.9, 1000, 'Casa do Chocolate'),
  ing(8, 4, 'Cacau em pó 100%', 'g', 1500, 500, 39.9, 500, 'Casa do Chocolate'),
  ing(9, 7, 'Creme de avelã', 'g', 0, 650, 34.9, 600, 'Casa do Chocolate'),
  ing(10, 5, 'Ovos', 'un', 52, 30, 21, 24, 'Granja Sol Nascente'),
  ing(11, 6, 'Óleo de soja', 'ml', 1800, 900, 7.4, 600, 'Atacado Central'),
  ing(12, 1, 'Fermento químico', 'g', 220, 100, 6.5, 100, 'Atacado Central'),
  ing(13, 3, 'Doce de leite', 'g', 2100, 400, 11.9, 800, 'Laticínios Vale Verde'),
  ing(14, 8, 'Caixa kraft com visor', 'un', 38, 50, 62, 20, 'Embalagens Feliz'),
];

// ── 3. Custos fixos e configurações (base do rateio no preço)
const settings = { hourly_rate: 12, monthly_units: 420, min_margin_pct: 30 };
const fixedCosts = [
  { id: 1, category_id: 40, name: 'Conta de luz', kind: 'variable', amount: 238.4 },
  { id: 2, category_id: 41, name: 'Conta de água', kind: 'variable', amount: 96.7 },
  { id: 3, category_id: 42, name: 'Gás de cozinha', kind: 'variable', amount: 130 },
  { id: 4, category_id: 43, name: 'Internet', kind: 'fixed', amount: 99.9 },
  { id: 5, category_id: 44, name: 'Anúncios no Instagram', kind: 'fixed', amount: 180 },
];
const fixedMonthly = fixedCosts.reduce((s, c) => s + c.amount, 0);

// ── 4. Receitas, com o preço calculado pelo mesmo código do painel
// ids 1 a 4 têm foto (a API fictícia serve as imagens de frontend/public/fotos); o resto cai no símbolo da marca
const recipeDefs = [
  [1, 'Cookie de Ninho com Nutella', 20, 12, 60, 1.2, 110, 14, [[1, 300], [2, 220], [4, 200], [10, 2], [9, 240], [12, 6]]],
  [2, 'Brownie Tradicional', 21, 12, 70, 1, 90, 12, [[3, 300], [4, 250], [7, 300], [8, 80], [10, 5], [1, 160]]],
  [3, 'Bolo de Cenoura com Brigadeiro', 22, 1, 80, 1.2, 900, 68, [[1, 360], [3, 280], [10, 4], [11, 180], [12, 12], [7, 200], [5, 395], [6, 100]]],
  [4, 'Torta de Limão', 23, 1, 90, 1.2, 800, 54, [[1, 220], [4, 110], [5, 395], [6, 200], [3, 60], [10, 2]]],
  [5, 'Cookie Red Velvet', 20, 12, 60, 1.2, 110, 13, [[1, 300], [2, 200], [4, 200], [10, 2], [8, 20], [12, 6]]],
  [6, 'Cookie de Chocolate Belga', 20, 12, 60, 1.2, 110, 15, [[1, 300], [2, 220], [4, 200], [10, 2], [7, 300], [12, 6]]],
  [7, 'Brownie de Doce de Leite', 21, 12, 70, 1, 90, 13, [[3, 300], [4, 250], [7, 200], [8, 80], [10, 5], [13, 300]]],
  [8, 'Bolo de Chocolate Trufado', 22, 1, 80, 1.5, 1000, 78, [[1, 300], [3, 300], [10, 4], [11, 150], [8, 90], [7, 150], [6, 200]]],
  [9, 'Brigadeiros Gourmet (caixa com 12)', 24, 6, 90, 1, 0, 42, [[5, 3160], [8, 240], [4, 160], [14, 6]]],
];
const unitCostOf = (id) => { const i = ingredients.find((x) => x.id === id); return i.package_size > 0 ? i.package_cost / i.package_size : 0; };
const recipes = recipeDefs.map(([id, name, category_id, yield_units, margin_pct, prep_hours, unit_weight_g, sale_price, items]) => {
  const row = {
    id, name, category_id, yield_units, margin_pct, prep_hours, notes: '', unit_weight_g, sale_price,
    photo_v: id <= 4 ? '1' : null, show_on_menu: true, tags: [],
    items: items.map(([ingredient_id, qty]) => ({ ingredient_id, qty })),
    variants: [{ id: id * 10, name: 'Receita 1', position: 0, yield_units, prep_hours, is_default: true, items: items.map(([ingredient_id, qty]) => ({ ingredient_id, qty })) }],
  };
  const base = calcRecipe({
    items: items.map(([ingredient_id, qty]) => ({ qty, unitCost: unitCostOf(ingredient_id) })),
    yieldUnits: yield_units, marginPct: margin_pct, prepHours: prep_hours, hourlyRate: settings.hourly_rate,
    fixedMonthly, monthlyUnits: settings.monthly_units,
  });
  return { ...row, pricing: withSalePrice(base, sale_price) };
});

// ── 5. Pedidos (os que o painel lista: alguns vieram do app/site, outros foram lançados à mão)
const ord = (id, number, customer, recipe_id, qty, delivery, status, payment, extra = {}) => {
  const r = recipes.find((x) => x.id === recipe_id);
  return {
    id, number, order_date: day(-Math.max(1, Math.round(-delivery) + 2)), customer, recipe_id, product: r.name, qty, unit_price: r.sale_price,
    delivery_date: day(delivery), status, payment, notes: '', address: '', ...extra,
  };
};
const orders = [
  ord(7, 107, 'Maria Exemplo', 1, 4, 1, 'producao', 'pago', { notes: 'Sem castanhas' }),
  ord(6, 106, 'Carlos Teste', 3, 1, 1, 'novo', 'pago', {}),
  ord(5, 105, 'Ana Modelo', 4, 1, 2, 'novo', 'sinal', { notes: 'Para um aniversário. Entregar às 15h.' }),
  ord(4, 104, 'Bruno Fictício', 9, 3, 3, 'novo', 'pendente', { notes: 'Lembrancinhas de casamento.' }),
  ord(3, 103, 'Carla Demonstração', 2, 12, 0, 'pronto', 'pago', {}),
  ord(2, 102, 'Diego Amostra', 6, 6, -3, 'entregue', 'pago', {}),
  ord(1, 101, 'Elisa Exemplo', 8, 1, -6, 'entregue', 'pago', {}),
];

// ── 6. Resumo, metas e cardápio
const goal = { revenue: 5000, orders: 60, new_customers: 12, avg_ticket: 85 };
const stats = { revenue: 3842.5, profit: 2216.3, orders: 47, avg_ticket: 81.75, new_customers: 9 };
const low = ingredients.filter((i) => stockStatus(i.stock_qty, i.min_stock) !== 'ok');
const dashboard = {
  username: owner.username, month: monthKey, ingredientCount: ingredients.length, goal, stats,
  receivable: orders.filter((o) => o.status !== 'cancelado' && o.payment !== 'pago').reduce((s, o) => s + o.qty * o.unit_price, 0), empty: false,
  low: low.slice(0, 6),
  upcoming: orders.filter((o) => !['entregue', 'cancelado'].includes(o.status)).sort((a, b) => a.delivery_date.localeCompare(b.delivery_date)).slice(0, 5),
  best: [...recipes].sort((a, b) => b.pricing.realMarginPct - a.pricing.realMarginPct).slice(0, 5),
  refundsWaiting: 0, refundsPending: 0, refundsFailed: 0,
};
const cardapio = recipes.map((r) => ({ id: r.id, name: r.name, priced: true, price: r.sale_price, photo_v: r.photo_v, show_on_menu: true, show_ingredients: r.id === 1, tags: ['contem_gluten', 'contem_ovos'] }));
const coupons = [
  { id: 1, code: 'BEMVINDO10', percent: 10, min_total: null, min_inclusive: true, active: true, uses: 14 },
  { id: 2, code: 'SEXTOU15', percent: 15, min_total: 60, min_inclusive: true, active: true, uses: 6 },
];

// ── 7. Rotas (todas exigem sessão; o mock-api.mjs cuida disso)
export const gestaoRoutes = {
  '/api/me': () => owner,
  '/api/dashboard': () => dashboard,
  '/api/categories': (url) => { const k = url.searchParams.get('kind'); return k ? categories.filter((c) => c.kind === k) : categories; },
  '/api/ingredients': () => ingredients,
  '/api/recipes': () => recipes,
  '/api/recipes/cardapio': () => cardapio,
  '/api/orders': () => orders,
  '/api/goals': () => ({ goal, stats }),
  '/api/settings': () => settings,
  '/api/fixed-costs': () => fixedCosts,
  '/api/coupons': () => coupons,
  '/api/combos': () => ({ combos: [], products: [], discount_pct: 10, min_margin_pct: 30 }),
  '/api/price-alerts': () => [],
  '/api/devolucoes': () => [],
  '/api/pickup-addresses': () => [],
  '/api/suppliers': () => [],
  '/api/feedback': () => [],
  '/api/privacy-requests': () => [],
  '/api/historico': () => [],
};
