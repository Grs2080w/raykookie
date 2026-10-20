/// Prospecção: planejamento de preço por quantidade. Funções puras. Porte de shared/prospection.ts (os mesmos casos de teste valem nos dois).
///
/// Regras:
///   bruto     = qtd × preço unitário
///   desconto  = % sobre o bruto, ou R$ no item; faixa por quantidade (maior minQty ≤ qtd) substitui o desconto simples
///   líquido   = bruto − desconto
///   imposto   = % sobre o BRUTO (antes do desconto)
///   frete     = rateado entre os itens proporcionalmente ao bruto (a soma fecha exatamente no frete total)
///   lucro     = líquido − imposto − custo − extras − frete
///   margem    = lucro ÷ líquido (sobre a venda) · markup = lucro ÷ (custo + extras + frete) (sobre o custo)
///   preço pela margem alvo: preço = custo unitário × (1 + markup%) — markup sobre o custo do produto, antes de desconto e imposto.
library;

import 'dart:convert';
import 'dart:typed_data';

enum DiscountMode { pct, brl }

enum PriceMode { price, markup }

enum Level { ok, low, negative }

class Tier {
  const Tier(this.minQty, this.pct);
  final double minQty, pct;

  Map<String, dynamic> toJson() => {'minQty': minQty, 'pct': pct};
  factory Tier.fromJson(Map<String, dynamic> j) => Tier(_num(j['minQty']), _num(j['pct']));
}

double _num(Object? v) => v is num && v.isFinite ? v.toDouble() : 0;

class ProspectItem {
  const ProspectItem({
    required this.id,
    required this.name,
    this.qty = 1,
    this.cost = 0,
    this.priceMode = PriceMode.price,
    this.price = 0,
    this.markupPct,
    this.discountMode,
    this.discount = 0,
    this.useTiers = false,
    this.taxPct,
    this.extra = 0,
  });

  final String id, name;
  final double qty, cost, price, discount, extra;
  final PriceMode priceMode;
  final double? markupPct, taxPct; // null = usa o geral
  final DiscountMode? discountMode; // null = usa o desconto geral
  final bool useTiers;

  ProspectItem copyWith({
    String? name,
    double? qty,
    double? cost,
    PriceMode? priceMode,
    double? price,
    double? markupPct,
    bool clearMarkup = false,
    DiscountMode? discountMode,
    bool clearDiscountMode = false,
    double? discount,
    bool? useTiers,
    double? taxPct,
    bool clearTax = false,
    double? extra,
  }) => ProspectItem(
    id: id,
    name: name ?? this.name,
    qty: qty ?? this.qty,
    cost: cost ?? this.cost,
    priceMode: priceMode ?? this.priceMode,
    price: price ?? this.price,
    markupPct: clearMarkup ? null : (markupPct ?? this.markupPct),
    discountMode: clearDiscountMode ? null : (discountMode ?? this.discountMode),
    discount: discount ?? this.discount,
    useTiers: useTiers ?? this.useTiers,
    taxPct: clearTax ? null : (taxPct ?? this.taxPct),
    extra: extra ?? this.extra,
  );

  Map<String, dynamic> toJson() => {
    'id': id, 'name': name, 'qty': qty, 'cost': cost, 'priceMode': priceMode.name, 'price': price, 'markupPct': markupPct,
    'discountMode': discountMode?.name, 'discount': discount, 'useTiers': useTiers, 'taxPct': taxPct, 'extra': extra,
  };

  factory ProspectItem.fromJson(Map<String, dynamic> j) => ProspectItem(
    id: '${j['id']}',
    name: '${j['name'] ?? ''}',
    qty: _num(j['qty']),
    cost: _num(j['cost']),
    priceMode: j['priceMode'] == 'markup' ? PriceMode.markup : PriceMode.price,
    price: _num(j['price']),
    markupPct: j['markupPct'] is num ? (j['markupPct'] as num).toDouble() : null,
    discountMode: j['discountMode'] == 'pct' ? DiscountMode.pct : (j['discountMode'] == 'brl' ? DiscountMode.brl : null),
    discount: _num(j['discount']),
    useTiers: j['useTiers'] == true,
    taxPct: j['taxPct'] is num ? (j['taxPct'] as num).toDouble() : null,
    extra: _num(j['extra']),
  );
}

class ProspectGlobals {
  const ProspectGlobals({
    this.discountMode = DiscountMode.pct,
    this.discount = 0,
    this.taxPct = 0,
    this.freight = 0,
    this.targetMarkupPct = 100,
    this.minMarginPct = 20,
    this.tiers = const [],
  });

  final DiscountMode discountMode;
  final double discount, taxPct, freight, targetMarkupPct, minMarginPct;
  final List<Tier> tiers;

  ProspectGlobals copyWith({
    DiscountMode? discountMode,
    double? discount,
    double? taxPct,
    double? freight,
    double? targetMarkupPct,
    double? minMarginPct,
    List<Tier>? tiers,
  }) => ProspectGlobals(
    discountMode: discountMode ?? this.discountMode,
    discount: discount ?? this.discount,
    taxPct: taxPct ?? this.taxPct,
    freight: freight ?? this.freight,
    targetMarkupPct: targetMarkupPct ?? this.targetMarkupPct,
    minMarginPct: minMarginPct ?? this.minMarginPct,
    tiers: tiers ?? this.tiers,
  );

  Map<String, dynamic> toJson() => {
    'discountMode': discountMode.name, 'discount': discount, 'taxPct': taxPct, 'freight': freight,
    'targetMarkupPct': targetMarkupPct, 'minMarginPct': minMarginPct, 'tiers': tiers.map((t) => t.toJson()).toList(),
  };

  factory ProspectGlobals.fromJson(Map<String, dynamic> j) => ProspectGlobals(
    discountMode: j['discountMode'] == 'brl' ? DiscountMode.brl : DiscountMode.pct,
    discount: _num(j['discount']),
    taxPct: _num(j['taxPct']),
    freight: _num(j['freight']),
    targetMarkupPct: j['targetMarkupPct'] is num ? (j['targetMarkupPct'] as num).toDouble() : 100,
    minMarginPct: j['minMarginPct'] is num ? (j['minMarginPct'] as num).toDouble() : 20,
    tiers: [for (final t in (j['tiers'] as List? ?? const [])) if (t is Map<String, dynamic>) Tier.fromJson(t)],
  );
}

class ProspectRow {
  const ProspectRow({
    required this.id,
    required this.name,
    required this.qty,
    required this.cost,
    required this.unitPrice,
    required this.gross,
    required this.discountValue,
    required this.discountPct,
    required this.net,
    required this.finalUnit,
    required this.tax,
    required this.costTotal,
    required this.extra,
    required this.freight,
    required this.profit,
    required this.marginPct,
    required this.markupPct,
    required this.level,
    this.issues = const [],
  });

  final String id, name;
  final double qty, cost, unitPrice, gross, discountValue, discountPct, net, finalUnit, tax, costTotal, extra, freight, profit, marginPct, markupPct;
  final Level level;
  final List<String> issues;
}

class ProspectResult {
  const ProspectResult(this.rows, this.totals);
  final List<ProspectRow> rows;
  final ProspectRow totals;
}

/// Arredonda em centavos (evita 0,1 + 0,2 ≠ 0,3 nas somas).
double round2(double v) => (v * 100).roundToDouble() / 100;

double _nonNeg(double v) => v.isFinite && v > 0 ? v : 0;
double _clampPct(double v) => _nonNeg(v) > 100 ? 100 : _nonNeg(v);

/// Desconto % da faixa que vale para a quantidade (maior minQty ≤ qtd); 0 se nenhuma.
double tierPct(List<Tier> tiers, double qty) {
  Tier? best;
  for (final t in tiers) {
    if (_nonNeg(t.minQty) > 0 && qty >= t.minQty && (best == null || t.minQty > best.minQty)) best = t;
  }
  return best == null ? 0 : _clampPct(best.pct);
}

/// Preço unitário a partir do custo e do markup sobre o custo: 10 com 100% → 20.
double priceFromMarkup(double cost, double markupPct) => _nonNeg(cost) * (1 + _nonNeg(markupPct) / 100);

/// Preço unitário necessário para uma margem sobre a venda (custo 70, margem 30% → 100). Margem ≥ 100% não existe → 0.
double priceFromMargin(double cost, double marginPct) {
  final m = _nonNeg(marginPct);
  return m >= 100 ? 0 : _nonNeg(cost) / (1 - m / 100);
}

/// Rateia [total] entre [weights] proporcionalmente, em centavos, e a soma fecha exatamente. Pesos zerados dividem por igual.
List<double> allocate(double total, List<double> weights) {
  final n = weights.length;
  if (n == 0) return [];
  final cents = (_nonNeg(total) * 100).round();
  final w = weights.map(_nonNeg).toList();
  final sum = w.fold<double>(0, (s, x) => s + x);
  final raw = [for (final x in w) sum > 0 ? cents * x / sum : cents / n];
  final out = [for (final r in raw) r.floor()];
  var left = cents - out.fold<int>(0, (s, x) => s + x);
  // devolve os centavos que sobraram a quem tem a maior parte fracionária
  final order = List<int>.generate(n, (i) => i)
    ..sort((a, b) {
      final c = (raw[b] - raw[b].floorToDouble()).compareTo(raw[a] - raw[a].floorToDouble());
      return c != 0 ? c : a.compareTo(b);
    });
  for (var k = 0; left > 0; k = (k + 1) % n, left--) {
    out[order[k]]++;
  }
  return [for (final c in out) c / 100];
}

Level _levelOf(double marginPct, double net, double minMarginPct, double profit) =>
    profit < 0 ? Level.negative : (net > 0 && marginPct < minMarginPct ? Level.low : Level.ok);

/// Calcula todas as linhas e os totais. Entradas ruins (negativo, vazio, NaN) viram 0 e geram um aviso em `issues`.
ProspectResult calcProspect(List<ProspectItem> items, ProspectGlobals g) {
  // ── 1. preço, bruto, desconto e líquido de cada linha
  final base = <_Base>[];
  for (final it in items) {
    final issues = <String>[];
    double fix(String label, double v) {
      if (!v.isFinite) {
        issues.add('$label inválido: considerei 0.');
        return 0;
      }
      if (v < 0) {
        issues.add('$label não pode ser negativo: considerei 0.');
        return 0;
      }
      return v;
    }

    final qty = fix('Quantidade', it.qty);
    if (qty == 0) issues.add('Informe a quantidade.');
    final cost = fix('Custo', it.cost);
    final extra = fix('Custos extras', it.extra);
    var unitPrice = it.priceMode == PriceMode.markup ? priceFromMarkup(cost, it.markupPct ?? g.targetMarkupPct) : fix('Preço de venda', it.price);
    unitPrice = round2(unitPrice);
    if (unitPrice == 0 && qty > 0) issues.add('Sem preço de venda.');
    final gross = round2(qty * unitPrice);

    final mode = it.discountMode ?? g.discountMode;
    final raw = it.discountMode != null ? it.discount : g.discount;
    final tier = it.useTiers ? tierPct(g.tiers, qty) : 0.0;
    double discountValue;
    if (tier > 0) {
      discountValue = gross * (tier / 100);
    } else if (mode == DiscountMode.pct) {
      if (_nonNeg(raw) > 100) issues.add('Desconto acima de 100%: limitei a 100%.');
      discountValue = gross * (_clampPct(raw) / 100);
    } else {
      discountValue = _nonNeg(raw);
    }
    if (discountValue > gross) {
      if (gross > 0 && mode == DiscountMode.brl) issues.add('Desconto maior que o total: limitei ao total.');
      discountValue = gross;
    }
    discountValue = round2(discountValue);
    final net = round2(gross - discountValue);
    final tax = round2(gross * (_clampPct(it.taxPct ?? g.taxPct) / 100));
    base.add(_Base(it, qty, cost, extra, unitPrice, gross, discountValue, net, tax, round2(qty * cost), issues));
  }

  // ── 2. frete rateado pelo bruto
  final freight = allocate(g.freight, [for (final b in base) b.gross]);

  // ── 3. lucro, margem e markup por linha
  final rows = <ProspectRow>[];
  for (var i = 0; i < base.length; i++) {
    final b = base[i];
    final profit = round2(b.net - b.tax - b.costTotal - b.extra - freight[i]);
    final spent = b.costTotal + b.extra + freight[i];
    final marginPct = b.net > 0 ? profit / b.net * 100 : 0.0;
    rows.add(ProspectRow(
      id: b.it.id, name: b.it.name, qty: b.qty, cost: b.cost, unitPrice: b.unitPrice, gross: b.gross, discountValue: b.discountValue,
      discountPct: b.gross > 0 ? b.discountValue / b.gross * 100 : 0, net: b.net, finalUnit: b.qty > 0 ? round2(b.net / b.qty) : 0,
      tax: b.tax, costTotal: b.costTotal, extra: b.extra, freight: freight[i], profit: profit, marginPct: marginPct,
      markupPct: spent > 0 ? profit / spent * 100 : 0, level: _levelOf(marginPct, b.net, g.minMarginPct, profit), issues: b.issues,
    ));
  }

  // ── 4. totais (soma das linhas já arredondadas: a tabela fecha com o rodapé)
  double sum(double Function(ProspectRow) f) => round2(rows.fold<double>(0, (s, r) => s + f(r)));
  final gross = sum((r) => r.gross), disc = sum((r) => r.discountValue), net = sum((r) => r.net), profit = sum((r) => r.profit);
  final costT = sum((r) => r.costTotal), extra = sum((r) => r.extra), fr = sum((r) => r.freight);
  final spent = costT + extra + fr;
  final marginPct = net > 0 ? profit / net * 100 : 0.0;
  return ProspectResult(
    rows,
    ProspectRow(
      id: 'total', name: 'TOTAL', qty: sum((r) => r.qty), cost: 0, unitPrice: 0, gross: gross, discountValue: disc,
      discountPct: gross > 0 ? disc / gross * 100 : 0, net: net, finalUnit: 0, tax: sum((r) => r.tax), costTotal: costT, extra: extra,
      freight: fr, profit: profit, marginPct: marginPct, markupPct: spent > 0 ? profit / spent * 100 : 0,
      level: rows.isEmpty ? Level.ok : _levelOf(marginPct, net, g.minMarginPct, profit),
    ),
  );
}

class _Base {
  _Base(this.it, this.qty, this.cost, this.extra, this.unitPrice, this.gross, this.discountValue, this.net, this.tax, this.costTotal, this.issues);
  final ProspectItem it;
  final double qty, cost, extra, unitPrice, gross, discountValue, net, tax, costTotal;
  final List<String> issues;
}

// ── CSV (Excel brasileiro) ────────────────────────────────────────────────────────────────────────

/// Número como o Excel BR lê: vírgula decimal, sem separador de milhar.
String _csvNum(double v, [int digits = 2]) => v.isFinite ? v.toStringAsFixed(digits).replaceAll('.', ',') : '';

/// Protege a célula: aspas quando tem ; " ou quebra de linha, e apóstrofo se começa como fórmula (= + - @).
String csvCell(String v) {
  final s = RegExp(r'^[=+\-@\t\r]').hasMatch(v) ? "'$v" : v;
  return RegExp(r'[;"\r\n]').hasMatch(s) ? '"${s.replaceAll('"', '""')}"' : s;
}

const csvHeader = [
  'Produto', 'Qtd', 'Custo unit.', 'Preço unit.', 'Desconto (R\$)', 'Desconto (%)', 'Preço final unit.', 'Total bruto',
  'Total líquido', 'Impostos', 'Custo total', 'Custos extras', 'Frete rateado', 'Lucro', 'Margem (%)', 'Markup (%)',
];

/// CSV com ";" de separador, vírgula decimal, BOM UTF-8 e linha de totais. Só entra o que está em [rows].
String toCsv(List<ProspectRow> rows, ProspectRow totals) {
  String line(List<String> cells) => cells.map(csvCell).join(';');
  String qty(double n) => _csvNum(n, 3).replaceAll(RegExp(r',?0+$'), ''); // 10 → "10", 2,5 → "2,5"
  final body = [
    for (final r in rows)
      line([
        r.name, qty(r.qty), _csvNum(r.cost), _csvNum(r.unitPrice), _csvNum(r.discountValue), _csvNum(r.discountPct),
        _csvNum(r.finalUnit), _csvNum(r.gross), _csvNum(r.net), _csvNum(r.tax), _csvNum(r.costTotal), _csvNum(r.extra),
        _csvNum(r.freight), _csvNum(r.profit), _csvNum(r.marginPct), _csvNum(r.markupPct),
      ]),
  ];
  final foot = line([
    'TOTAL', qty(totals.qty), '', '', _csvNum(totals.discountValue), _csvNum(totals.discountPct), '', _csvNum(totals.gross),
    _csvNum(totals.net), _csvNum(totals.tax), _csvNum(totals.costTotal), _csvNum(totals.extra), _csvNum(totals.freight),
    _csvNum(totals.profit), _csvNum(totals.marginPct), _csvNum(totals.markupPct),
  ]);
  return '﻿${[line(csvHeader), ...body, foot].join('\r\n')}\r\n';
}

/// Bytes UTF-8 do CSV (o BOM já está no texto).
Uint8List csvBytes(String csv) => Uint8List.fromList(utf8.encode(csv));

/// prospeccao_2026-10-19.csv
String csvFileName(String isoDate) => 'prospeccao_$isoDate.csv';
