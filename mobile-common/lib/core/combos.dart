/// Preço de combo, igual ao do servidor (ComboPricing): sugerido = soma avulsa menos o desconto de combo, nunca
/// abaixo do custo + margem mínima, arredondado para cima em R$ 0,50; o preço escolhido vale no lugar dele.
library;

class ComboPart {
  const ComboPart({required this.qty, required this.price, required this.unitCost});
  final int qty;
  final double price, unitCost;
}

class ComboCalc {
  const ComboCalc({required this.fullPrice, required this.cost, required this.suggested, required this.price, required this.profit, required this.marginPct});
  final double fullPrice, cost, suggested, price, profit, marginPct;
  bool get belowCost => profit < 0;
  double get savings => fullPrice - price > 0 ? fullPrice - price : 0;
}

double _cents(double v) => (v * 100).round() / 100;

/// Arredonda para cima em R$ 0,50 (sem o ruído de ponto flutuante).
double roundUpHalf(double v) => ((v * 100).round() / 50).ceil() / 2;

ComboCalc comboCalc(List<ComboPart> parts, {required double discountPct, required double minMarginPct, double? fixedPrice}) {
  var full = 0.0, cost = 0.0;
  for (final p in parts) {
    full += p.qty * p.price;
    cost += p.qty * p.unitCost;
  }
  final discounted = full * (1 - discountPct / 100);
  final floor = cost * (1 + minMarginPct / 100);
  final suggested = roundUpHalf(discounted > floor ? discounted : floor);
  final price = fixedPrice != null && fixedPrice > 0 ? fixedPrice : suggested;
  final profit = price - cost;
  final margin = cost > 0 ? profit / cost * 100 : 0.0;
  return ComboCalc(fullPrice: _cents(full), cost: _cents(cost), suggested: suggested, price: _cents(price), profit: _cents(profit), marginPct: (margin * 10).round() / 10);
}
