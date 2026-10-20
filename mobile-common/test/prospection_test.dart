import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/prospection.dart';

// Mesmos casos de tests/prospection/prospection.test.mjs: o app e o site precisam calcular igual.
ProspectItem item({
  String id = 'a',
  String name = 'Brigadeiro',
  double qty = 10,
  double cost = 2,
  double price = 5,
  PriceMode priceMode = PriceMode.price,
  double? markupPct,
  DiscountMode? discountMode,
  double discount = 0,
  bool useTiers = false,
  double? taxPct,
  double extra = 0,
}) => ProspectItem(
  id: id, name: name, qty: qty, cost: cost, price: price, priceMode: priceMode, markupPct: markupPct,
  discountMode: discountMode, discount: discount, useTiers: useTiers, taxPct: taxPct, extra: extra,
);

ProspectResult calc(List<ProspectItem> items, [ProspectGlobals g = const ProspectGlobals()]) => calcProspect(items, g);

void main() {
  group('preço pela margem e pelo markup', () {
    test('markup sobre o custo: 10 com 100% → 20', () => expect(priceFromMarkup(10, 100), 20));
    test('markup 0% mantém o custo', () => expect(priceFromMarkup(10, 0), 10));
    test('margem sobre a venda: custo 70 e 30% → 100', () => expect(priceFromMargin(70, 30), closeTo(100, 1e-9)));
    test('margem de 100% ou mais não existe (sem divisão por zero)', () {
      expect(priceFromMargin(10, 100), 0);
      expect(priceFromMargin(10, 150), 0);
    });
    test('negativos viram 0', () => expect(priceFromMarkup(-5, -10), 0));
    test('item em modo markup usa o markup próprio ou o alvo global', () {
      expect(calc([item(priceMode: PriceMode.markup, markupPct: 50)]).rows[0].unitPrice, 3);
      expect(calc([item(priceMode: PriceMode.markup)], const ProspectGlobals(targetMarkupPct: 100)).rows[0].unitPrice, 4);
    });
  });

  group('desconto', () {
    test('sem desconto: líquido = bruto', () {
      final r = calc([item()]).rows[0];
      expect([r.gross, r.net, r.discountValue], [50, 50, 0]);
    });
    test('percentual no item (10% de 50 = 5)', () {
      final r = calc([item(discountMode: DiscountMode.pct, discount: 10)]).rows[0];
      expect([r.discountValue, r.net, r.finalUnit], [5, 45, 4.5]);
      expect(r.discountPct, closeTo(10, 1e-9));
    });
    test('em reais no item (total da linha)', () {
      final r = calc([item(discountMode: DiscountMode.brl, discount: 8)]).rows[0];
      expect([r.net, r.finalUnit], [42, 4.2]);
    });
    test('desconto geral vale para quem não sobrescreve; o item sobrescreve', () {
      const g = ProspectGlobals(discountMode: DiscountMode.pct, discount: 20);
      final rows = calc([item(), item(id: 'b', discountMode: DiscountMode.pct, discount: 0)], g).rows;
      expect([rows[0].net, rows[1].net], [40, 50]);
    });
    test('desconto em R\$ maior que o total é limitado ao total e avisa', () {
      final r = calc([item(discountMode: DiscountMode.brl, discount: 999)]).rows[0];
      expect([r.net, r.discountValue], [0, 50]);
      expect(r.issues.any((s) => s.contains('maior que o total')), isTrue);
    });
    test('percentual acima de 100 é limitado a 100', () {
      final r = calc([item(discountMode: DiscountMode.pct, discount: 250)]).rows[0];
      expect(r.net, 0);
      expect(r.issues.any((s) => s.contains('100%')), isTrue);
    });
    test('desconto negativo é ignorado', () => expect(calc([item(discountMode: DiscountMode.pct, discount: -10)]).rows[0].net, 50));
  });

  group('faixas progressivas por quantidade', () {
    const tiers = [Tier(10, 5), Tier(50, 10)];
    test('escolhe a maior faixa atingida', () {
      expect([for (final q in <double>[9, 10, 49, 50, 500]) tierPct(tiers, q)], [0, 5, 5, 10, 10]);
    });
    test('não depende da ordem em que as faixas foram digitadas', () => expect(tierPct(tiers.reversed.toList(), 60), 10));
    test('faixa com minQty 0 ou inválida é ignorada', () => expect(tierPct(const [Tier(0, 90), Tier(double.nan, 90)], 5), 0));
    test('aplica no item e substitui o desconto simples', () {
      final r = calc([item(qty: 50, useTiers: true, discountMode: DiscountMode.pct, discount: 1)], const ProspectGlobals(tiers: tiers)).rows[0];
      expect(r.discountPct, closeTo(10, 1e-9));
      expect(r.net, 225);
    });
    test('abaixo da primeira faixa cai no desconto simples', () {
      final r = calc([item(qty: 5, useTiers: true, discountMode: DiscountMode.pct, discount: 2)], const ProspectGlobals(tiers: tiers)).rows[0];
      expect(r.discountPct, closeTo(2, 1e-9));
    });
    test('sem useTiers, as faixas não valem', () => expect(calc([item(qty: 50)], const ProspectGlobals(tiers: tiers)).rows[0].discountValue, 0));
  });

  group('imposto, lucro, margem e markup', () {
    test('imposto incide sobre o BRUTO, não sobre o líquido', () {
      final r = calc([item(discountMode: DiscountMode.pct, discount: 20, taxPct: 10)]).rows[0];
      expect([r.tax, r.net], [5, 40]);
    });
    test('lucro = líquido − imposto − custo − extras − frete', () {
      expect(calc([item(taxPct: 10, extra: 3)], const ProspectGlobals(freight: 4)).rows[0].profit, 18);
    });
    test('margem sobre a venda e markup sobre o custo', () {
      final r = calc([item()]).rows[0];
      expect(r.profit, 30);
      expect(r.marginPct, closeTo(60, 1e-9));
      expect(r.markupPct, closeTo(150, 1e-9));
    });
    test('imposto do item sobrescreve o geral', () {
      final rows = calc([item(), item(id: 'b', taxPct: 0)], const ProspectGlobals(taxPct: 10)).rows;
      expect([rows[0].tax, rows[1].tax], [5, 0]);
    });
    test('totais somam as linhas e a margem total é lucro ÷ líquido total', () {
      final r = calc([item(), item(id: 'b', cost: 4, price: 5)]);
      expect([r.totals.gross, r.totals.net], [100, 100]);
      expect(r.totals.profit, r.rows[0].profit + r.rows[1].profit);
      expect(r.totals.marginPct, closeTo(40, 1e-9));
    });
  });

  group('alertas de margem', () {
    test('ok / baixa / negativa', () {
      expect(calc([item()]).rows[0].level, Level.ok);
      expect(calc([item(cost: 4.5)]).rows[0].level, Level.low);
      expect(calc([item(cost: 6)]).rows[0].level, Level.negative);
    });
    test('mínimo configurável', () => expect(calc([item()], const ProspectGlobals(minMarginPct: 70)).rows[0].level, Level.low));
    test('margem exatamente no mínimo não alerta', () => expect(calc([item(cost: 4)]).rows[0].level, Level.ok));
  });

  group('rateio do frete', () {
    test('proporcional ao bruto e fecha exatamente no total', () {
      final r = calc([item(price: 5), item(id: 'b', price: 15)], const ProspectGlobals(freight: 40));
      expect([r.rows[0].freight, r.rows[1].freight, r.totals.freight], [10, 30, 40]);
    });
    test('centavos que sobram são distribuídos (1,00 entre 3)', () {
      final out = allocate(1, [1, 1, 1]);
      expect(out.fold<double>(0, (s, x) => s + x), closeTo(1, 1e-9));
      expect((out.toList()..sort()), [0.33, 0.33, 0.34]);
    });
    test('soma sempre igual ao total, mesmo com pesos quebrados', () {
      expect(allocate(100, [33.33, 66.67, 12.5, 7.77]).fold<double>(0, (s, x) => s + x), closeTo(100, 1e-9));
    });
    test('bruto zero em todos: divide por igual', () => expect(allocate(10, [0, 0]), [5, 5]));
    test('frete negativo vira 0; lista vazia não quebra', () {
      expect(allocate(-10, [1, 1]), [0, 0]);
      expect(allocate(10, []), isEmpty);
    });
    test('frete reduz o lucro total', () {
      expect(calc([item()]).totals.profit - calc([item()], const ProspectGlobals(freight: 7)).totals.profit, 7);
    });
  });

  group('casos extremos', () {
    test('quantidade 0: nada de divisão por zero', () {
      final r = calc([item(qty: 0)]).rows[0];
      expect([r.finalUnit, r.marginPct], [0, 0]);
      expect(r.issues.any((s) => s.toLowerCase().contains('quantidade')), isTrue);
    });
    test('preço 0 com custo: prejuízo, margem 0 e sem NaN', () {
      final r = calc([item(price: 0)]).rows[0];
      expect([r.level, r.marginPct], [Level.negative, 0]);
      expect([r.gross, r.net, r.profit, r.markupPct].every((v) => v.isFinite), isTrue);
    });
    test('custo 0: markup 0, sem divisão por zero', () {
      final r = calc([item(cost: 0)]).rows[0];
      expect([r.markupPct, r.profit], [0, 50]);
    });
    test('NaN, negativos e vazio viram 0 com aviso', () {
      final r = calc([item(qty: double.nan, cost: -3, price: -1)]).rows[0];
      expect(r.gross, 0);
      expect(r.issues.length, greaterThanOrEqualTo(2));
    });
    test('sem itens: totais zerados', () {
      final r = calc([]);
      expect([r.rows.length, r.totals.net, r.totals.level], [0, 0, Level.ok]);
    });
    test('precisão: 0,1 + 0,2 não vira 0,30000000000000004', () {
      expect(calc([item(qty: 1, price: 0.1, cost: 0), item(id: 'b', qty: 1, price: 0.2, cost: 0)]).totals.gross, 0.3);
    });
  });

  group('CSV', () {
    ProspectResult sample() => calc([
      item(name: 'Brigadeiro', discountMode: DiscountMode.pct, discount: 10),
      item(id: 'b', name: 'Bolo; "grande"', qty: 2.5, cost: 10, price: 30),
    ]);
    test('começa com BOM UTF-8, usa ";" e vírgula decimal', () {
      final s = sample();
      final csv = toCsv(s.rows, s.totals);
      expect(csv.codeUnitAt(0), 0xfeff);
      final lines = csv.substring(1).trim().split('\r\n');
      expect(lines.length, 4);
      expect(lines[0].startsWith('Produto;Qtd;'), isTrue);
      expect(lines[1].startsWith('Brigadeiro;10;2,00;5,00;5,00;10,00;4,50;50,00;45,00'), isTrue);
      expect(lines[3].startsWith('TOTAL;12,5;'), isTrue);
      expect(RegExp(r'\d\.\d').hasMatch(lines[1]), isFalse);
    });
    test('escapa ; e aspas no nome', () => expect(csvCell('Bolo; "grande"'), '"Bolo; ""grande"""'));
    test('neutraliza fórmula do Excel (=, +, -, @)', () {
      for (final s in ['=1+1', '+cmd', '-2', '@x']) {
        expect(csvCell(s).startsWith("'"), isTrue);
      }
    });
    test('só exporta as linhas recebidas (as selecionadas)', () {
      final s = sample();
      expect(toCsv(s.rows.take(1).toList(), s.totals).trim().split('\r\n').length, 3);
    });
    test('nome do arquivo leva a data', () => expect(csvFileName('2026-10-19'), 'prospeccao_2026-10-19.csv'));
  });

  group('JSON (simulações salvas)', () {
    test('item e globais sobrevivem a ida e volta', () {
      final it = item(discountMode: DiscountMode.brl, discount: 3, taxPct: 4, useTiers: true, priceMode: PriceMode.markup, markupPct: 30);
      final back = ProspectItem.fromJson(it.toJson());
      expect(back.toJson(), it.toJson());
      const g = ProspectGlobals(freight: 9, tiers: [Tier(10, 5)], discountMode: DiscountMode.brl);
      expect(ProspectGlobals.fromJson(g.toJson()).toJson(), g.toJson());
    });
    test('JSON estragado não quebra', () {
      final back = ProspectItem.fromJson({'id': 1, 'qty': 'x', 'cost': null});
      expect([back.qty, back.cost], [0, 0]);
      expect(ProspectGlobals.fromJson({}).minMarginPct, 20);
    });
  });
}
