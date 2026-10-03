import { describe, expect, it } from 'vitest';
import { calcRecipe, roundUpHalf, stockStatus, unitCost } from '@shared/pricing';

describe('pricing', () => {
  it('custo por unidade: R$5 / 200g = R$0,025/g', () => expect(unitCost(5, 200)).toBeCloseTo(0.025));
  it('custo por unidade com embalagem 0 não quebra', () => expect(unitCost(5, 0)).toBe(0));
  it('arredonda para cima em R$0,50', () => {
    expect(roundUpHalf(10.01)).toBe(10.5);
    expect(roundUpHalf(10.5)).toBe(10.5);
    expect(roundUpHalf(10.51)).toBe(11);
  });
  it('status de estoque', () => {
    expect(stockStatus(0, 1)).toBe('zerado');
    expect(stockStatus(1, 1)).toBe('baixo');
    expect(stockStatus(2, 1)).toBe('ok');
  });
  it('receita completa', () => {
    const r = calcRecipe({
      items: [{ qty: 200, unitCost: 0.025 }, { qty: 500, unitCost: 0.0359 }], // 5 + 17,95
      yieldUnits: 10, marginPct: 100, prepHours: 2, hourlyRate: 15,
      fixedMonthly: 500, monthlyUnits: 100,
    });
    expect(r.ingredients).toBeCloseTo(22.95);
    expect(r.labor).toBe(30);
    expect(r.overhead).toBeCloseTo(50);
    expect(r.total).toBeCloseTo(102.95);
    expect(r.perUnit).toBeCloseTo(10.295);
    expect(r.suggested).toBeCloseTo(20.59);
    expect(r.rounded).toBe(21);
    expect(r.profitPerUnit).toBeCloseTo(10.705);
    expect(r.profitTotal).toBeCloseTo(107.05);
  });
  it('receita vazia não gera NaN', () => {
    const r = calcRecipe({ items: [], yieldUnits: 0, marginPct: 50, prepHours: 0, hourlyRate: 0, fixedMonthly: 0, monthlyUnits: 0 });
    expect(Object.values(r).every((v) => Number.isFinite(v))).toBe(true);
  });
});
