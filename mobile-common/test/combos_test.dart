import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/combos.dart';

void main() {
  test('combo: soma avulsa menos o desconto, arredondado para cima em R\$ 0,50 (igual ao servidor)', () {
    final r = comboCalc(const [ComboPart(qty: 4, price: 9, unitCost: 3), ComboPart(qty: 2, price: 12, unitCost: 4)], discountPct: 10, minMarginPct: 30);
    expect(r.fullPrice, 60);
    expect(r.cost, 20);
    expect(r.suggested, 54);
    expect(r.profit, 34);
    expect(r.marginPct, 170);
    expect(r.savings, 6);
    expect(comboCalc(const [ComboPart(qty: 3, price: 7.30, unitCost: 2)], discountPct: 10, minMarginPct: 30).suggested, 20);
  });

  test('combo: piso de custo + margem mínima; preço escolhido vale e avisa abaixo do custo', () {
    expect(comboCalc(const [ComboPart(qty: 2, price: 10, unitCost: 8)], discountPct: 50, minMarginPct: 30).suggested, 21);
    final fixed = comboCalc(const [ComboPart(qty: 2, price: 10, unitCost: 8)], discountPct: 10, minMarginPct: 30, fixedPrice: 15);
    expect(fixed.price, 15);
    expect(fixed.belowCost, isTrue);
  });
}
