// Gera backend-java/rules-vectors/pricing.json a partir de backend/shared/{pricing,margin,pack}.ts.
// Uso único: node --experimental-strip-types infra/rules-vectors/gen-pricing.mjs
// Cada receita grava a entrada, o resultado de calcRecipe e o de withSalePrice (quando há preço fixado);
// os casos de margem gravam o resultado de marginAlert; os de embalagem, parsePack e pricePerLabel.
// Os números saem no formato do JSON.stringify (o menor texto que volta ao mesmo double), então o Java
// consegue comparar o double exato, não "quase igual".
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { calcRecipe, withSalePrice } from '../../backend/shared/pricing.ts';
import { marginAlert } from '../../backend/shared/margin.ts';
import { parsePack, pricePerLabel } from '../../backend/shared/pack.ts';

// Gerador com semente (mulberry32): o arquivo sai igual a cada execução.
let seed = 20261007;
const rand = () => {
  seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
const pick = (list) => list[Math.floor(rand() * list.length)];
const dec = (max, places) => Math.round(rand() * max * 10 ** places) / 10 ** places;

// Uma receita como a confeiteira cadastra: valores com as casas das colunas NUMERIC do banco.
const randomRecipe = () => {
  const items = Array.from({ length: Math.floor(rand() * 7) }, () => {
    const size = pick([1, 12, 30, 200, 395, 500, 1000, 1010, 5000]);
    const cost = dec(80, 2);
    return { qty: dec(1500, 3), unitCost: size > 0 ? cost / size : 0 };
  });
  return {
    input: {
      items,
      yieldUnits: pick([0, 1, 3, 10, 12, 20, 24, 50, dec(40, 3)]),
      marginPct: pick([0, 30, 50, 100, 150, dec(300, 2)]),
      prepHours: pick([0, 0.5, 1, 2, dec(10, 2)]),
      hourlyRate: pick([0, 15, 20, 37.5, dec(80, 2)]),
      fixedMonthly: pick([0, 350, 1200.5, dec(5000, 2)]),
      monthlyUnits: pick([0, 100, 250, 1000, dec(3000, 2)]),
    },
    sale: pick([null, null, 5, 12.5, 49.9, dec(200, 2)]),
  };
};

// Os casos de backend/tests/pricing.test.ts e pack-margin.test.ts, mais as bordas.
const fixed = [
  { input: { items: [{ qty: 200, unitCost: 0.025 }, { qty: 500, unitCost: 0.0359 }], yieldUnits: 10, marginPct: 100, prepHours: 2, hourlyRate: 15, fixedMonthly: 500, monthlyUnits: 100 }, sale: null },
  { input: { items: [], yieldUnits: 0, marginPct: 50, prepHours: 0, hourlyRate: 0, fixedMonthly: 0, monthlyUnits: 0 }, sale: null },
  { input: { items: [{ qty: 500, unitCost: 0.032 }], yieldUnits: 10, marginPct: 100, prepHours: 0, hourlyRate: 0, fixedMonthly: 0, monthlyUnits: 100 }, sale: 4 },
  { input: { items: [{ qty: 500, unitCost: 0.04 }], yieldUnits: 10, marginPct: 100, prepHours: 0, hourlyRate: 0, fixedMonthly: 0, monthlyUnits: 100 }, sale: 10 },
  { input: { items: [{ qty: 1, unitCost: 10 }], yieldUnits: 1, marginPct: 0, prepHours: 0, hourlyRate: 0, fixedMonthly: 0, monthlyUnits: 100 }, sale: null },
  { input: { items: [{ qty: 1, unitCost: 10.01 }], yieldUnits: 1, marginPct: 0, prepHours: 0, hourlyRate: 0, fixedMonthly: 0, monthlyUnits: 100 }, sale: null },
  { input: { items: [{ qty: 1, unitCost: 10.51 }], yieldUnits: 1, marginPct: 0, prepHours: 0, hourlyRate: 0, fixedMonthly: 0, monthlyUnits: 100 }, sale: null },
  { input: { items: [{ qty: 0, unitCost: 3 }], yieldUnits: 4, marginPct: 100, prepHours: 0, hourlyRate: 0, fixedMonthly: 0, monthlyUnits: 100 }, sale: 7 },
];

const recipes = [...fixed, ...Array.from({ length: 200 }, randomRecipe)].map(({ input, sale }) => {
  const pricing = calcRecipe(input);
  const row = { input, pricing };
  if (sale !== null) Object.assign(row, { sale, withSale: withSalePrice(pricing, sale) });
  return row;
});

// Alertas: a mesma receita antes e depois de o ingrediente mudar de preço, com e sem preço fixado.
const alerts = [];
for (let k = 0; k < 120; k++) {
  const base = randomRecipe();
  if (base.input.items.length === 0) base.input.items.push({ qty: dec(800, 3), unitCost: dec(0.09, 5) });
  const factor = pick([0.8, 0.97, 1, 1.02, 1.05, 1.1, 1.3, 2]);
  const changed = { ...base.input, items: base.input.items.map((it, i) => (i === 0 ? { ...it, unitCost: it.unitCost * factor } : it)) };
  const b0 = calcRecipe(base.input), a0 = calcRecipe(changed);
  const sale = pick([null, b0.rounded, dec(60, 2)]);
  const withSale = !!(sale && sale > 0);
  const minMarginPct = pick([0, 30, 50, 80]);
  const opts = { withSale, minMarginPct, suggestedAfter: a0.rounded };
  alerts.push({ before: base.input, after: changed, sale, minMarginPct, alert: marginAlert(withSalePrice(b0, sale), withSalePrice(a0, sale), opts) });
}

const packNames = [
  'JAZAM COLORETI 1 KG', 'ACUCAR IMPALPAVEL CONFEITEIRO 1KG HARALD', 'Chocolate em pó 50% 1,01kg', 'Granulado colorido 400 g',
  'Leite condensado 395g', 'Leite integral 1L', 'Essência de baunilha 30 ml', 'Creme de leite UHT 1,5 L', 'Chantilly 2x500g',
  'PIC PIC BALAO 8 VIOLETA 50 UN', 'SACO TERMICO HOT PACK ESPETINHO 100 UNS', 'Forminha nº 4 cx c/ 12 unidades',
  'Vela Balão Branca Número 3 - SilverFestas', '', 'Farinha 5kg', 'Manteiga 200gr', 'Óleo 900 ml', 'Água 1,5lt', 'Leite 12 x 1 L',
  'Açúcar 1.000 g', 'Cacau 1.000,5 g', 'Embalagem pct 50 pcs', 'Caixa com 25 unid.', 'Pote 250ml c/ tampa 10un', 'Fermento 0g',
  'Chocolate 2,1 kg 3x', 'Corante gel 15G', 'Granulado 1.5kg', 'Doce 3×200g', 'Fita 10 peças',
];
const packs = packNames.map((name) => ({ name, pack: parsePack(name) }));
const labels = [[44.99, 1000, 'g'], [8, 1000, 'ml'], [14.65, 50, 'un'], [0, 0, 'g'], [12.3, 395, 'g'], [3.333, 7, 'un']]
  .map(([price, size, unit]) => ({ price, size, unit, label: pricePerLabel(price, size, unit) }));

const out = fileURLToPath(new URL('../../backend-java/rules-vectors/pricing.json', import.meta.url));
writeFileSync(out, JSON.stringify({ recipes, alerts, packs, labels }, null, 1) + '\n');
console.log(`${recipes.length} receitas, ${alerts.length} alertas, ${packs.length} embalagens -> ${out}`);
