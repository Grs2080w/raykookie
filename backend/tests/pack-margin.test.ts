import { describe, expect, it } from 'vitest';
import { calcRecipe, withSalePrice, type PricingInput } from '@shared/pricing';
import { marginAlert } from '@shared/margin';
import { parsePack, pricePerLabel } from '@shared/pack';

describe('tamanho da embalagem no nome do produto', () => {
  const t = (name: string, size: number, unit: string) => expect(parsePack(name)).toEqual({ size, unit });
  it('peso', () => {
    t('JAZAM COLORETI 1 KG', 1000, 'g');
    t('ACUCAR IMPALPAVEL CONFEITEIRO 1KG HARALD', 1000, 'g');
    t('Chocolate em pó 50% 1,01kg', 1010, 'g');
    t('Granulado colorido 400 g', 400, 'g');
    t('Leite condensado 395g', 395, 'g');
  });
  it('volume', () => {
    t('Leite integral 1L', 1000, 'ml');
    t('Essência de baunilha 30 ml', 30, 'ml');
    t('Creme de leite UHT 1,5 L', 1500, 'ml');
  });
  it('multiplicação e unidades', () => {
    t('Chantilly 2x500g', 1000, 'g');
    t('PIC PIC BALAO 8 VIOLETA 50 UN', 50, 'un');
    t('SACO TERMICO HOT PACK ESPETINHO 100 UNS', 100, 'un');
    t('Forminha nº 4 cx c/ 12 unidades', 12, 'un');
  });
  it('sem tamanho: nulo (a confeiteira informa)', () => {
    expect(parsePack('Vela Balão Branca Número 3 - SilverFestas')).toBeNull();
    expect(parsePack('')).toBeNull();
  });
  it('preço por kg/L/un para comparar', () => {
    expect(pricePerLabel(44.99, 1000, 'g')).toBe('R$ 44,99/kg');
    expect(pricePerLabel(8, 1000, 'ml')).toBe('R$ 8,00/L');
    expect(pricePerLabel(14.65, 50, 'un')).toBe('R$ 0,29/un');
  });
});

describe('alerta de margem', () => {
  const input = (unitCost: number): PricingInput => ({ items: [{ qty: 500, unitCost }], yieldUnits: 10, marginPct: 100, prepHours: 0, hourlyRate: 0, fixedMonthly: 0, monthlyUnits: 100 });
  const before = calcRecipe(input(0.032)); // chocolate R$ 32/kg
  const after = calcRecipe(input(0.04)); // subiu para R$ 40/kg

  it('preço fixado: avisa quando a margem cai para baixo do mínimo', () => {
    const sale = before.rounded; // R$ 3,50 (custo/un 1,60)
    const a = marginAlert(withSalePrice(before, sale), withSalePrice(after, sale), { withSale: true, minMarginPct: 50, suggestedAfter: after.rounded });
    expect(a).toMatchObject({ kind: 'margem', suggested: after.rounded });
    expect(a!.after).toBeLessThan(a!.before);
  });
  it('preço fixado: queda pequena e acima do mínimo não avisa', () => {
    const sale = 10;
    expect(marginAlert(withSalePrice(before, sale), withSalePrice(after, sale), { withSale: true, minMarginPct: 30, suggestedAfter: after.rounded })).toBeNull();
  });
  it('sem preço fixado: avisa quando o preço sugerido sobe 5% ou mais', () => {
    expect(marginAlert(before, after, { withSale: false, minMarginPct: 30, suggestedAfter: after.rounded })).toMatchObject({ kind: 'preco', before: before.rounded, after: after.rounded });
  });
  it('preço do ingrediente caiu: nada a avisar', () => {
    expect(marginAlert(after, before, { withSale: false, minMarginPct: 30, suggestedAfter: before.rounded })).toBeNull();
    expect(marginAlert(withSalePrice(after, 5), withSalePrice(before, 5), { withSale: true, minMarginPct: 30, suggestedAfter: before.rounded })).toBeNull();
  });
  it('withSalePrice refaz lucro e margem com o preço fixado', () => {
    const p = withSalePrice(before, 4);
    expect(p.rounded).toBe(4);
    expect(p.profitPerUnit).toBeCloseTo(4 - before.perUnit);
    expect(p.realMarginPct).toBeCloseTo(((4 - before.perUnit) / 4) * 100);
    expect(withSalePrice(before, null)).toBe(before);
  });
});
