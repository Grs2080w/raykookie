// Cálculos de custo e preço. Funções puras, usadas no cliente (tempo real) e no servidor.

export type Unit = 'g' | 'ml' | 'un';

export function unitCost(packageCost: number, packageSize: number): number {
  return packageSize > 0 ? packageCost / packageSize : 0;
}

export type StockStatus = 'ok' | 'baixo' | 'zerado';
export function stockStatus(stockQty: number, minStock: number): StockStatus {
  if (stockQty <= 0) return 'zerado';
  if (stockQty <= minStock) return 'baixo';
  return 'ok';
}

export type PricingInput = {
  items: { qty: number; unitCost: number }[];
  yieldUnits: number;
  marginPct: number; // acréscimo sobre o custo por unidade
  prepHours: number;
  hourlyRate: number;
  fixedMonthly: number; // soma dos gastos fixos/variáveis do mês
  monthlyUnits: number; // unidades produzidas por mês (base do rateio)
};

export type PricingResult = {
  ingredients: number;
  labor: number;
  overhead: number;
  total: number;
  perUnit: number;
  suggested: number;
  rounded: number;
  profitPerUnit: number;
  profitTotal: number;
  realMarginPct: number; // lucro sobre o preço (arredondado)
};

export const roundUpHalf = (v: number) => Math.ceil(v * 2 - 1e-9) / 2;

export function calcRecipe(i: PricingInput): PricingResult {
  const yieldUnits = i.yieldUnits > 0 ? i.yieldUnits : 1;
  const ingredients = i.items.reduce((s, it) => s + it.qty * it.unitCost, 0);
  const labor = i.prepHours * i.hourlyRate;
  const overhead = i.monthlyUnits > 0 ? (i.fixedMonthly / i.monthlyUnits) * yieldUnits : 0;
  const total = ingredients + labor + overhead;
  const perUnit = total / yieldUnits;
  const suggested = perUnit * (1 + i.marginPct / 100);
  const rounded = perUnit > 0 ? roundUpHalf(suggested) : 0;
  const profitPerUnit = rounded - perUnit;
  return {
    ingredients, labor, overhead, total, perUnit, suggested, rounded,
    profitPerUnit,
    profitTotal: profitPerUnit * yieldUnits,
    realMarginPct: rounded > 0 ? (profitPerUnit / rounded) * 100 : 0,
  };
}

export type GoalRow = { key: string; target: number; actual: number };
export function goalProgress(target: number, actual: number) {
  const pct = target > 0 ? (actual / target) * 100 : 0;
  return { diff: actual - target, pct, hit: target > 0 && actual >= target };
}

/** Preço do cardápio fixado pela confeiteira: vale no lugar do sugerido, e lucro e margem são refeitos com ele. */
export function withSalePrice(p: PricingResult, sale: number | null | undefined): PricingResult {
  if (!(sale && sale > 0) || p.perUnit <= 0) return p;
  const yieldUnits = p.total / p.perUnit;
  const profitPerUnit = sale - p.perUnit;
  return { ...p, rounded: sale, profitPerUnit, profitTotal: profitPerUnit * yieldUnits, realMarginPct: (profitPerUnit / sale) * 100 };
}
