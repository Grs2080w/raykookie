import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/prospection.dart';
import 'package:raykookie_common/ui/theme.dart';

/// Tabela final da Prospecção, só com o que vai no print: título, data de geração, os produtos e a linha de totais.
/// Em tela larga é uma tabela; no celular cada produto vira um bloco (tudo cabe sem rolar para o lado).
class ProspectionTableScreen extends StatelessWidget {
  const ProspectionTableScreen({super.key, required this.title, required this.result, required this.onCsv, this.now});
  final String title;
  final ProspectResult result;
  final VoidCallback onCsv;
  final DateTime? now;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final date = DateFormat('dd/MM/yyyy HH:mm').format(now ?? DateTime.now());
    return Scaffold(
      appBar: AppBar(
        title: const Text('Tabela final'),
        actions: [IconButton(tooltip: 'Exportar CSV', onPressed: onCsv, icon: const Icon(Icons.ios_share_rounded))],
      ),
      body: LayoutBuilder(
        builder: (context, box) => SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 980),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: display(context, size: 28)),
                  const SizedBox(height: 2),
                  Text('Gerada em $date', style: TextStyle(color: c.muted)),
                  Divider(color: c.ink, thickness: 2, height: 24),
                  if (box.maxWidth >= 640) _WideTable(result: result) else _Cards(result: result),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

Color _tone(BuildContext context, Level l) => switch (l) {
  Level.ok => context.c.ok,
  Level.low => context.c.warn,
  Level.negative => context.c.bad,
};

String _qty(double n) => number(n);
String _disc(ProspectRow r) => r.discountValue > 0 ? '${percent2(r.discountPct)}\n− ${money(r.discountValue)}' : '—';

class _WideTable extends StatelessWidget {
  const _WideTable({required this.result});
  final ProspectResult result;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    TextStyle head() => TextStyle(fontWeight: FontWeight.w800, fontSize: 12, color: c.ink, letterSpacing: 0.4);
    Widget cell(String t, {TextAlign align = TextAlign.right, TextStyle? style}) => Padding(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 10),
      child: Text(t, textAlign: align, style: style ?? const TextStyle(fontSize: 15, fontFeatures: [FontFeature.tabularFigures()])),
    );
    TableRow row(ProspectRow r, {bool total = false}) {
      final bold = TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: total ? null : null, fontFeatures: const [FontFeature.tabularFigures()]);
      final tone = bold.copyWith(color: _tone(context, r.level));
      return TableRow(
        decoration: BoxDecoration(border: Border(top: BorderSide(color: total ? c.ink : c.line, width: total ? 2 : 1))),
        children: [
          cell(r.name, align: TextAlign.left, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
          cell(_qty(r.qty), style: total ? bold : null),
          cell(total ? '' : money(r.unitPrice)),
          cell(_disc(r), style: total ? bold : null),
          cell(total ? '' : money(r.finalUnit), style: bold),
          cell(money(r.net), style: bold),
          cell(money(r.profit), style: tone),
          cell(percent2(r.marginPct), style: tone),
        ],
      );
    }

    return Table(
      columnWidths: const {0: FlexColumnWidth(2.4), 1: FlexColumnWidth(0.8), 2: FlexColumnWidth(1.3), 3: FlexColumnWidth(1.3), 4: FlexColumnWidth(1.4), 5: FlexColumnWidth(1.4), 6: FlexColumnWidth(1.3), 7: FlexColumnWidth(1.1)},
      children: [
        TableRow(children: [
          cell('PRODUTO', align: TextAlign.left, style: head()), cell('QTD', style: head()), cell('PREÇO UNIT.', style: head()),
          cell('DESCONTO', style: head()), cell('PREÇO FINAL UNIT.', style: head()), cell('TOTAL', style: head()),
          cell('LUCRO', style: head()), cell('MARGEM', style: head()),
        ]),
        for (final r in result.rows) row(r),
        row(result.totals, total: true),
      ],
    );
  }
}

class _Cards extends StatelessWidget {
  const _Cards({required this.result});
  final ProspectResult result;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    Widget kv(String k, String v, {bool strong = false, Color? color}) => SizedBox(
      width: 140,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(k, style: TextStyle(color: c.muted, fontSize: 12)),
        Text(v.replaceAll('\n', ' '), style: TextStyle(fontWeight: strong ? FontWeight.w700 : FontWeight.w500, color: color, fontFeatures: const [FontFeature.tabularFigures()])),
      ]),
    );
    Widget block(ProspectRow r, {bool total = false}) => Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(18), border: Border.all(color: total ? c.ink : c.line, width: total ? 2 : 1)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(r.name, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800)),
        const SizedBox(height: 8),
        Wrap(spacing: 12, runSpacing: 8, children: [
          kv('Qtd', _qty(r.qty)),
          if (!total) kv('Preço unit.', money(r.unitPrice)),
          kv('Desconto', _disc(r)),
          if (!total) kv('Preço final unit.', money(r.finalUnit), strong: true),
          kv('Total', money(r.net), strong: true),
          kv('Lucro', money(r.profit), strong: true, color: _tone(context, r.level)),
          kv('Margem', percent2(r.marginPct), strong: true, color: _tone(context, r.level)),
        ]),
      ]),
    );
    return Column(children: [for (final r in result.rows) block(r), block(result.totals, total: true)]);
  }
}
