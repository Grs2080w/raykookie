// Prospecção: planejamento de preço por quantidade. Funções puras (sem tela, sem rede), usadas no site.
// O app tem o porte idêntico em mobile-common/lib/core/prospection.dart; os mesmos casos de teste valem nos dois.
//
// Regras (decididas com a dona):
//   bruto     = qtd × preço unitário
//   desconto  = % sobre o bruto, ou R$ no item; faixa por quantidade (maior minQty ≤ qtd) substitui o desconto simples
//   líquido   = bruto − desconto
//   imposto   = % sobre o BRUTO (antes do desconto)
//   frete     = rateado entre os itens proporcionalmente ao bruto (a soma fecha exatamente no frete total)
//   lucro     = líquido − imposto − custo − extras − frete
//   margem    = lucro ÷ líquido (sobre a venda) · markup = lucro ÷ (custo + extras + frete) (sobre o custo)
//   preço pela margem alvo: preço = custo unitário × (1 + markup%) — markup sobre o custo do produto, antes de desconto e imposto.

export type DiscountMode = 'pct' | 'brl';
export type Tier = { minQty: number; pct: number };
export type PriceMode = 'price' | 'markup';

export type ProspectItem = {
  id: string;
  name: string;
  qty: number;
  cost: number; // custo unitário (R$)
  priceMode: PriceMode; // 'price': usa `price`; 'markup': preço sai de cost × (1 + markupPct/100)
  price: number; // preço de venda unitário (R$)
  markupPct: number | null; // null = usa o markup alvo global
  discountMode: DiscountMode | null; // null = usa o desconto geral
  discount: number; // % ou R$ (total da linha), conforme discountMode
  useTiers: boolean; // aplica as faixas por quantidade
  taxPct: number | null; // null = usa o imposto geral
  extra: number; // custos extras da linha (R$)
};

export type ProspectGlobals = {
  discountMode: DiscountMode;
  discount: number;
  taxPct: number;
  freight: number; // frete total (R$), rateado entre os itens
  targetMarkupPct: number; // markup alvo dos itens em modo 'markup'
  minMarginPct: number; // abaixo disso a linha é sinalizada
  tiers: Tier[];
};

export type Level = 'ok' | 'low' | 'negative';

export type ProspectRow = {
  id: string;
  name: string;
  qty: number;
  cost: number;
  unitPrice: number; // preço unitário efetivo (digitado ou calculado pelo markup)
  gross: number;
  discountValue: number;
  discountPct: number; // desconto efetivo em % do bruto
  net: number;
  finalUnit: number; // líquido ÷ qtd
  tax: number;
  costTotal: number;
  extra: number;
  freight: number;
  profit: number;
  marginPct: number; // sobre a venda (líquido)
  markupPct: number; // sobre o custo
  level: Level;
  issues: string[]; // avisos de entrada (valores corrigidos, campos zerados)
};

export type ProspectTotals = Omit<ProspectRow, 'id' | 'name' | 'unitPrice' | 'finalUnit' | 'cost' | 'level' | 'issues'> & { level: Level };

export const DEFAULT_GLOBALS: ProspectGlobals = {
  discountMode: 'pct', discount: 0, taxPct: 0, freight: 0, targetMarkupPct: 100, minMarginPct: 20, tiers: [],
};

export const newItem = (over: Partial<ProspectItem> & Pick<ProspectItem, 'id' | 'name'>): ProspectItem => ({
  qty: 1, cost: 0, priceMode: 'price', price: 0, markupPct: null, discountMode: null, discount: 0,
  useTiers: false, taxPct: null, extra: 0, ...over,
});

/** Arredonda em centavos (evita 0,1 + 0,2 ≠ 0,3 nas somas). */
export const round2 = (v: number) => Math.round((v + Number.EPSILON) * 100) / 100;

/** Número válido e não negativo; senão 0 (campo vazio, NaN, negativo). */
const nonNeg = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) && v > 0 ? v : 0);
const clampPct = (v: unknown): number => Math.min(100, nonNeg(v));

/** Desconto % da faixa que vale para a quantidade (maior minQty ≤ qtd); 0 se nenhuma. */
export function tierPct(tiers: Tier[], qty: number): number {
  let best: Tier | null = null;
  for (const t of tiers) {
    if (nonNeg(t.minQty) > 0 && qty >= t.minQty && (!best || t.minQty > best.minQty)) best = t;
  }
  return best ? clampPct(best.pct) : 0;
}

/** Preço unitário a partir do custo e do markup sobre o custo: 10 com 100% → 20. */
export const priceFromMarkup = (cost: number, markupPct: number): number => nonNeg(cost) * (1 + nonNeg(markupPct) / 100);

/** Preço unitário necessário para uma margem sobre a venda (ex.: custo 70, margem 30% → 100). Margem ≥ 100% não existe → 0. */
export function priceFromMargin(cost: number, marginPct: number): number {
  const m = nonNeg(marginPct);
  return m >= 100 ? 0 : nonNeg(cost) / (1 - m / 100);
}

/** Rateia `total` entre `weights` proporcionalmente, em centavos, e a soma fecha exatamente. Pesos zerados dividem por igual. */
export function allocate(total: number, weights: number[]): number[] {
  const n = weights.length;
  if (n === 0) return [];
  const cents = Math.round(nonNeg(total) * 100);
  const w = weights.map(nonNeg);
  const sum = w.reduce((s, x) => s + x, 0);
  const raw = w.map((x) => (sum > 0 ? (cents * x) / sum : cents / n));
  const out = raw.map(Math.floor);
  let left = cents - out.reduce((s, x) => s + x, 0);
  // devolve os centavos que sobraram a quem tem a maior parte fracionária
  const order = raw.map((r, i) => ({ i, f: r - Math.floor(r) })).sort((a, b) => b.f - a.f || a.i - b.i);
  for (let k = 0; left > 0; k = (k + 1) % n, left--) out[order[k].i]++;
  return out.map((c) => c / 100);
}

const levelOf = (marginPct: number, net: number, minMarginPct: number, profit: number): Level =>
  profit < 0 ? 'negative' : net > 0 && marginPct < minMarginPct ? 'low' : 'ok';

/** Calcula todas as linhas e os totais. Entradas ruins (negativo, vazio, NaN) viram 0 e geram um aviso em `issues`. */
export function calcProspect(items: ProspectItem[], g: ProspectGlobals): { rows: ProspectRow[]; totals: ProspectTotals } {
  // ── 1. preço, bruto, desconto e líquido de cada linha
  const base = items.map((it) => {
    const issues: string[] = [];
    const fix = (label: string, v: unknown) => {
      if (v === null || v === undefined) return 0;
      if (typeof v !== 'number' || !Number.isFinite(v)) { issues.push(`${label} inválido: considerei 0.`); return 0; }
      if (v < 0) { issues.push(`${label} não pode ser negativo: considerei 0.`); return 0; }
      return v;
    };
    const qty = fix('Quantidade', it.qty);
    if (qty === 0) issues.push('Informe a quantidade.');
    const cost = fix('Custo', it.cost);
    const extra = fix('Custos extras', it.extra);
    let unitPrice: number;
    if (it.priceMode === 'markup') unitPrice = priceFromMarkup(cost, it.markupPct ?? g.targetMarkupPct);
    else unitPrice = fix('Preço de venda', it.price);
    unitPrice = round2(unitPrice);
    if (unitPrice === 0 && qty > 0) issues.push('Sem preço de venda.');
    const gross = round2(qty * unitPrice);

    const mode: DiscountMode = it.discountMode ?? g.discountMode;
    const raw = it.discountMode ? it.discount : g.discount;
    const tier = it.useTiers ? tierPct(g.tiers, qty) : 0;
    let discountValue: number;
    if (tier > 0) discountValue = gross * (tier / 100);
    else if (mode === 'pct') {
      if (nonNeg(raw) > 100) issues.push('Desconto acima de 100%: limitei a 100%.');
      discountValue = gross * (clampPct(raw) / 100);
    } else discountValue = nonNeg(raw);
    if (discountValue > gross) { if (gross > 0 && mode === 'brl') issues.push('Desconto maior que o total: limitei ao total.'); discountValue = gross; }
    discountValue = round2(discountValue);
    const net = round2(gross - discountValue);
    const taxPct = it.taxPct ?? g.taxPct;
    const tax = round2(gross * (clampPct(taxPct) / 100));
    return { it, qty, cost, extra, unitPrice, gross, discountValue, net, tax, costTotal: round2(qty * cost), issues };
  });

  // ── 2. frete rateado pelo bruto
  const freight = allocate(g.freight, base.map((b) => b.gross));

  // ── 3. lucro, margem e markup por linha
  const rows: ProspectRow[] = base.map((b, i) => {
    const profit = round2(b.net - b.tax - b.costTotal - b.extra - freight[i]);
    const spent = b.costTotal + b.extra + freight[i];
    const marginPct = b.net > 0 ? (profit / b.net) * 100 : 0;
    return {
      id: b.it.id, name: b.it.name, qty: b.qty, cost: b.cost, unitPrice: b.unitPrice,
      gross: b.gross, discountValue: b.discountValue, discountPct: b.gross > 0 ? (b.discountValue / b.gross) * 100 : 0,
      net: b.net, finalUnit: b.qty > 0 ? round2(b.net / b.qty) : 0, tax: b.tax,
      costTotal: b.costTotal, extra: b.extra, freight: freight[i], profit, marginPct,
      markupPct: spent > 0 ? (profit / spent) * 100 : 0,
      level: levelOf(marginPct, b.net, g.minMarginPct, profit), issues: b.issues,
    };
  });

  // ── 4. totais (soma das linhas já arredondadas: a tabela fecha com o rodapé)
  const sum = (k: 'qty' | 'gross' | 'discountValue' | 'net' | 'tax' | 'costTotal' | 'extra' | 'freight' | 'profit') =>
    round2(rows.reduce((s, r) => s + r[k], 0));
  const t = { qty: sum('qty'), gross: sum('gross'), discountValue: sum('discountValue'), net: sum('net'), tax: sum('tax'), costTotal: sum('costTotal'), extra: sum('extra'), freight: sum('freight'), profit: sum('profit') };
  const spent = t.costTotal + t.extra + t.freight;
  const marginPct = t.net > 0 ? (t.profit / t.net) * 100 : 0;
  return {
    rows,
    totals: {
      ...t, discountPct: t.gross > 0 ? (t.discountValue / t.gross) * 100 : 0, marginPct,
      markupPct: spent > 0 ? (t.profit / spent) * 100 : 0, level: rows.length ? levelOf(marginPct, t.net, g.minMarginPct, t.profit) : 'ok',
    },
  };
}

// ── CSV (Excel brasileiro) ────────────────────────────────────────────────────────────────────────

/** Número como o Excel BR lê: vírgula decimal, sem separador de milhar. */
const csvNum = (v: number, digits = 2) => (Number.isFinite(v) ? v.toFixed(digits).replace('.', ',') : '');

/** Protege a célula: aspas quando tem ; " ou quebra de linha, e apóstrofo se começa como fórmula (= + - @). */
export function csvCell(v: string): string {
  const s = /^[=+\-@\t\r]/.test(v) ? `'${v}` : v;
  return /[;"\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export const CSV_HEADER = [
  'Produto', 'Qtd', 'Custo unit.', 'Preço unit.', 'Desconto (R$)', 'Desconto (%)', 'Preço final unit.', 'Total bruto',
  'Total líquido', 'Impostos', 'Custo total', 'Custos extras', 'Frete rateado', 'Lucro', 'Margem (%)', 'Markup (%)',
];

/** CSV com ";" de separador, vírgula decimal, BOM UTF-8 e linha de totais. Só entra o que está em `rows`. */
export function toCsv(rows: ProspectRow[], totals: ProspectTotals): string {
  const line = (cells: string[]) => cells.map(csvCell).join(';');
  const qty = (n: number) => csvNum(n, 3).replace(/,?0+$/, ''); // 10 → "10", 2,5 → "2,5"
  const body = rows.map((r) => line([
    r.name, qty(r.qty), csvNum(r.cost), csvNum(r.unitPrice), csvNum(r.discountValue), csvNum(r.discountPct), csvNum(r.finalUnit),
    csvNum(r.gross), csvNum(r.net), csvNum(r.tax), csvNum(r.costTotal), csvNum(r.extra), csvNum(r.freight), csvNum(r.profit),
    csvNum(r.marginPct), csvNum(r.markupPct),
  ]));
  const foot = line([
    'TOTAL', qty(totals.qty), '', '', csvNum(totals.discountValue), csvNum(totals.discountPct), '', csvNum(totals.gross), csvNum(totals.net),
    csvNum(totals.tax), csvNum(totals.costTotal), csvNum(totals.extra), csvNum(totals.freight), csvNum(totals.profit),
    csvNum(totals.marginPct), csvNum(totals.markupPct),
  ]);
  return '﻿' + [line(CSV_HEADER), ...body, foot].join('\r\n') + '\r\n';
}

/** prospeccao_2026-10-19.csv */
export const csvFileName = (isoDate: string) => `prospeccao_${isoDate}.csv`;
