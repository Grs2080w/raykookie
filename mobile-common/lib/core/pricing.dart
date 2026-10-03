/// Cálculos de custo e preço. Porte de shared/pricing.ts (os mesmos casos de teste valem nos dois).
double unitCost(double packageCost, double packageSize) => packageSize > 0 ? packageCost / packageSize : 0;

enum StockStatus { ok, baixo, zerado }

StockStatus stockStatus(double stockQty, double minStock) {
  if (stockQty <= 0) return StockStatus.zerado;
  if (stockQty <= minStock) return StockStatus.baixo;
  return StockStatus.ok;
}

double roundUpHalf(double v) => ((v * 2 - 1e-9).ceilToDouble()) / 2;

class PricingItem {
  const PricingItem(this.qty, this.unitCost);
  final double qty;
  final double unitCost;
}

class PricingResult {
  const PricingResult({
    required this.ingredients, required this.labor, required this.overhead, required this.total, required this.perUnit,
    required this.suggested, required this.rounded, required this.profitPerUnit, required this.profitTotal, required this.realMarginPct,
  });
  final double ingredients, labor, overhead, total, perUnit, suggested, rounded, profitPerUnit, profitTotal, realMarginPct;

  factory PricingResult.fromJson(Map<String, dynamic> j) {
    double d(String k) => (j[k] as num?)?.toDouble() ?? 0;
    return PricingResult(
      ingredients: d('ingredients'), labor: d('labor'), overhead: d('overhead'), total: d('total'), perUnit: d('perUnit'),
      suggested: d('suggested'), rounded: d('rounded'), profitPerUnit: d('profitPerUnit'), profitTotal: d('profitTotal'), realMarginPct: d('realMarginPct'),
    );
  }
}

PricingResult calcRecipe({
  required List<PricingItem> items,
  required double yieldUnits,
  required double marginPct,
  required double prepHours,
  required double hourlyRate,
  required double fixedMonthly,
  required double monthlyUnits,
}) {
  final y = yieldUnits > 0 ? yieldUnits : 1.0;
  final ingredients = items.fold<double>(0, (s, it) => s + it.qty * it.unitCost);
  final labor = prepHours * hourlyRate;
  final overhead = monthlyUnits > 0 ? (fixedMonthly / monthlyUnits) * y : 0.0;
  final total = ingredients + labor + overhead;
  final perUnit = total / y;
  final suggested = perUnit * (1 + marginPct / 100);
  final rounded = perUnit > 0 ? roundUpHalf(suggested) : 0.0;
  final profitPerUnit = rounded - perUnit;
  return PricingResult(
    ingredients: ingredients, labor: labor, overhead: overhead, total: total, perUnit: perUnit, suggested: suggested, rounded: rounded,
    profitPerUnit: profitPerUnit, profitTotal: profitPerUnit * y, realMarginPct: rounded > 0 ? (profitPerUnit / rounded) * 100 : 0,
  );
}

class GoalProgress {
  const GoalProgress(this.diff, this.pct, this.hit);
  final double diff, pct;
  final bool hit;
}

GoalProgress goalProgress(double target, double actual) {
  final pct = target > 0 ? (actual / target) * 100 : 0.0;
  return GoalProgress(actual - target, pct, target > 0 && actual >= target);
}

/// Preço do cardápio fixado pela confeiteira: vale no lugar do sugerido; lucro e margem são refeitos com ele. Porte de withSalePrice (shared/pricing.ts).
PricingResult withSalePrice(PricingResult p, double? sale) {
  if (sale == null || sale <= 0 || p.perUnit <= 0) return p;
  final y = p.total / p.perUnit;
  final profit = sale - p.perUnit;
  return PricingResult(
    ingredients: p.ingredients, labor: p.labor, overhead: p.overhead, total: p.total, perUnit: p.perUnit, suggested: p.suggested, rounded: sale,
    profitPerUnit: profit, profitTotal: profit * y, realMarginPct: profit / sale * 100,
  );
}
