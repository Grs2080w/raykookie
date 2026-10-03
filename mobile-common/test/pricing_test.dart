import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/pricing.dart';

// Mesmos casos de backend/tests/pricing.test.ts: o app e o servidor precisam calcular igual.
void main() {
  test('custo por unidade: R\$5 / 200g = R\$0,025/g', () => expect(unitCost(5, 200), closeTo(0.025, 1e-9)));
  test('custo por unidade com embalagem 0 não quebra', () => expect(unitCost(5, 0), 0));
  test('arredonda para cima em R\$0,50', () {
    expect(roundUpHalf(10.01), 10.5);
    expect(roundUpHalf(10.5), 10.5);
    expect(roundUpHalf(10.51), 11);
  });
  test('status de estoque', () {
    expect(stockStatus(0, 1), StockStatus.zerado);
    expect(stockStatus(1, 1), StockStatus.baixo);
    expect(stockStatus(2, 1), StockStatus.ok);
  });
  test('receita completa', () {
    final r = calcRecipe(
      items: const [PricingItem(200, 0.025), PricingItem(500, 0.0359)],
      yieldUnits: 10, marginPct: 100, prepHours: 2, hourlyRate: 15, fixedMonthly: 500, monthlyUnits: 100,
    );
    expect(r.ingredients, closeTo(22.95, 1e-9));
    expect(r.labor, 30);
    expect(r.overhead, closeTo(50, 1e-9));
    expect(r.total, closeTo(102.95, 1e-9));
    expect(r.perUnit, closeTo(10.295, 1e-9));
    expect(r.suggested, closeTo(20.59, 1e-9));
    expect(r.rounded, 21);
    expect(r.profitPerUnit, closeTo(10.705, 1e-9));
    expect(r.profitTotal, closeTo(107.05, 1e-9));
  });
  test('receita vazia não gera NaN', () {
    final r = calcRecipe(items: const [], yieldUnits: 0, marginPct: 50, prepHours: 0, hourlyRate: 0, fixedMonthly: 0, monthlyUnits: 0);
    for (final v in [r.ingredients, r.labor, r.overhead, r.total, r.perUnit, r.suggested, r.rounded, r.profitPerUnit, r.profitTotal, r.realMarginPct]) {
      expect(v.isFinite, isTrue);
    }
  });
  test('progresso da meta', () {
    expect(goalProgress(0, 100).pct, 0);
    final g = goalProgress(200, 250);
    expect(g.hit, isTrue);
    expect(g.diff, 50);
    expect(g.pct, 125);
  });

  test('preço fixado vale no lugar do sugerido e refaz lucro e margem', () {
    final p = calcRecipe(items: [const PricingItem(500, 0.032)], yieldUnits: 10, marginPct: 100, prepHours: 0, hourlyRate: 0, fixedMonthly: 0, monthlyUnits: 100);
    final f = withSalePrice(p, 4);
    expect(f.rounded, 4);
    expect(f.profitPerUnit, closeTo(4 - p.perUnit, 1e-9));
    expect(f.realMarginPct, closeTo((4 - p.perUnit) / 4 * 100, 1e-9));
    expect(identical(withSalePrice(p, null), p), isTrue);
    expect(identical(withSalePrice(p, 0), p), isTrue);
  });
}
