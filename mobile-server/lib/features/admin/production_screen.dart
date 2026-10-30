import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

/// Abaixo (ou igual) disto o estoque de produção de uma receita aparece como "baixo".
const _lowUnits = 3;

double _n(Object? v) => v is num ? v.toDouble() : double.tryParse('$v') ?? 0;
double? _nn(Object? v) => v == null ? null : _n(v);

/// Uma receita no estoque de produção (`GET /api/production/stock`). Custo = só ingredientes; preço = o do cardápio.
class ProductionRow {
  ProductionRow(Map<String, dynamic> j)
    : id = (j['recipe_id'] as num).toInt(),
      name = '${j['name']}',
      qty = _n(j['qty']),
      unitCost = _nn(j['unit_cost']),
      price = _nn(j['price']),
      profitUnit = _nn(j['profit_unit']),
      marginPct = _nn(j['margin_pct']),
      canMake = (j['can_make'] as num?)?.toInt(),
      blocking = [for (final b in (j['blocking'] as List? ?? [])) '$b'];
  final int id;
  final String name;
  final double qty;
  final double? unitCost, price, profitUnit, marginPct;
  final int? canMake;
  final List<String> blocking;
  double get stockProfit => qty * (profitUnit ?? 0);
}

class _ProductionData {
  _ProductionData(this.rows, this.summary, this.runs);
  final List<ProductionRow> rows;
  final Map<String, dynamic> summary;
  final List<Map<String, dynamic>> runs;
}

/// Estoque de produção: unidades prontas por receita, registrar produção (desconta os ingredientes) e histórico.
class ProductionScreen extends StatelessWidget {
  const ProductionScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Estoque de produção'),
        leading: IconButton(icon: const Icon(Icons.arrow_back_rounded), onPressed: () => context.pop()),
      ),
      body: Loader<_ProductionData>(
        scroll: false, // a tela monta a própria rolagem (ListView)
        load: () async {
          final r = await Future.wait([api.get('/api/production/stock'), api.get('/api/production/runs')]);
          final stock = Map<String, dynamic>.from(r[0] as Map);
          return _ProductionData(
            [for (final e in stock['recipes'] as List) ProductionRow(Map<String, dynamic>.from(e as Map))],
            Map<String, dynamic>.from(stock['summary'] as Map),
            [for (final e in r[1] as List) Map<String, dynamic>.from(e as Map)],
          );
        },
        builder: (context, d, reload) => _ProductionView(data: d, reload: reload),
      ),
    );
  }
}

class _ProductionView extends StatefulWidget {
  const _ProductionView({required this.data, required this.reload});
  final _ProductionData data;
  final Future<void> Function() reload;
  @override
  State<_ProductionView> createState() => _ProductionViewState();
}

class _ProductionViewState extends State<_ProductionView> {
  bool _history = false;
  String _search = '', _sort = 'name';
  static const _sorts = {'name': 'Nome', 'qty': 'Quantidade', 'profit': 'Lucro do estoque', 'margin': 'Margem'};

  ApiClient get _api => context.read<Session>().ownerApi;

  Future<void> _produce([ProductionRow? row]) async {
    final done = await showFormSheet<bool>(
      context,
      title: 'Registrar produção',
      child: ProduceSheet(rows: widget.data.rows, initial: row?.id),
    );
    if (done == true) await widget.reload();
  }

  Future<void> _adjust(ProductionRow r) async {
    final done = await showFormSheet<bool>(context, title: 'Ajustar estoque de produção', child: _AdjustSheet(row: r));
    if (done == true) await widget.reload();
  }

  Future<void> _undo(Map<String, dynamic> run) async {
    final qty = number(_n(run['qty']));
    if (!await confirmDialog(
      context,
      title: 'Desfazer $qty × ${run['recipe']}?',
      message: 'Os ingredientes voltam para o estoque e as unidades saem do estoque de produção. Se parte delas já saiu do estoque, o sistema bloqueia.',
      confirmLabel: 'Desfazer produção',
    )) {
      return;
    }
    try {
      await _api.post('/api/production/runs/${run['id']}/undo');
      Toast.ok('Produção desfeita');
      await widget.reload();
    } on ApiException catch (e) {
      Toast.error(e.message);
    }
  }

  List<ProductionRow> get _rows {
    final list = widget.data.rows.where((r) => _search.isEmpty || r.name.toLowerCase().contains(_search.toLowerCase())).toList();
    int by(ProductionRow a, ProductionRow b) => switch (_sort) {
      'qty' => b.qty.compareTo(a.qty),
      'profit' => b.stockProfit.compareTo(a.stockProfit),
      'margin' => (b.marginPct ?? double.negativeInfinity).compareTo(a.marginPct ?? double.negativeInfinity),
      _ => a.name.toLowerCase().compareTo(b.name.toLowerCase()),
    };
    return list..sort(by);
  }

  Widget _stat(String label, String value, {Color? color}) {
    final c = context.c;
    return AppCard(
      padding: const EdgeInsets.all(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: TextStyle(color: c.muted, fontSize: 12.5)),
          FittedBox(fit: BoxFit.scaleDown, child: Text(value, style: display(context, size: 22, color: color))),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.data.summary;
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 96),
      children: [
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: 8,
          crossAxisSpacing: 8,
          childAspectRatio: 2.2,
          children: [
            _stat('Unidades em estoque', number(_n(s['units']))),
            _stat('Custo do estoque', money(_n(s['cost']))),
            _stat('Receita se vender tudo', money(_n(s['revenue']))),
            _stat('Lucro estimado', money(_n(s['profit'])), color: context.c.ok),
          ],
        ),
        const SizedBox(height: 14),
        BusyButton(label: 'Registrar produção', icon: Icons.factory_outlined, onPressed: _produce),
        const SizedBox(height: 12),
        SegmentedButton<bool>(
          segments: const [ButtonSegment(value: false, label: Text('Estoque')), ButtonSegment(value: true, label: Text('Histórico'))],
          selected: {_history},
          onSelectionChanged: (v) => setState(() => _history = v.first),
        ),
        const SizedBox(height: 12),
        if (_history)
          _HistoryView(rows: widget.data.rows, initial: widget.data.runs, onUndo: _undo, key: ValueKey(widget.data.runs.length))
        else ...[
          TextField(
            decoration: const InputDecoration(hintText: 'Buscar receita', prefixIcon: Icon(Icons.search_rounded)),
            onChanged: (v) => setState(() => _search = v),
          ),
          const SizedBox(height: 10),
          SelectField<String>(
            label: 'Ordenar por',
            value: _sort,
            items: [for (final e in _sorts.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
            onChanged: (v) => setState(() => _sort = v ?? 'name'),
          ),
          const SizedBox(height: 6),
          if (widget.data.rows.isEmpty)
            const EmptyState(icon: Icons.inventory_2_outlined, title: 'Nenhuma receita ainda', text: 'Cadastre receitas para controlar quantas unidades prontas você tem.')
          else
            for (final r in _rows) _line(r),
        ],
      ],
    );
  }

  Widget _line(ProductionRow r) {
    final c = context.c;
    final empty = r.qty <= 0, low = !empty && r.qty <= _lowUnits;
    String dash(String? v) => v ?? '—';
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: AppCard(
        borderColor: empty ? c.bad : null,
        padding: const EdgeInsets.fromLTRB(14, 12, 6, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Expanded(child: Text(r.name, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16))),
              if (empty) const AppBadge('Sem estoque', tone: 'bad') else if (low) const AppBadge('Estoque baixo', tone: 'warn'),
              const SizedBox(width: 8),
              Text('${number(r.qty)} un', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16, color: empty ? c.bad : low ? c.warn : null)),
              const SizedBox(width: 8),
            ]),
            if (r.canMake != null)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Text(
                  r.canMake == 0 ? 'Faltam ingredientes${r.blocking.isEmpty ? '' : ': ${r.blocking.join(', ')}'}' : 'Dá para produzir ${number(r.canMake!)} un',
                  style: TextStyle(color: r.canMake == 0 ? c.bad : c.muted, fontSize: 12.5),
                ),
              ),
            const SizedBox(height: 8),
            Wrap(spacing: 16, runSpacing: 4, children: [
              _kv('Custo unitário', dash(r.unitCost == null ? null : moneyPrecise(r.unitCost!))),
              _kv('Preço', dash(r.price == null ? null : money(r.price!))),
              _kv('Lucro / margem', r.profitUnit == null ? '—' : '${money(r.profitUnit!)} · ${percent(r.marginPct ?? 0)}'),
              _kv('Lucro do estoque', r.profitUnit == null ? '—' : money(r.stockProfit)),
              _kv('Investido', r.unitCost == null ? '—' : money(r.qty * r.unitCost!)),
            ]),
            Row(children: [
              TextButton.icon(onPressed: () => _produce(r), icon: const Icon(Icons.factory_outlined, size: 18), label: const Text('Produzir')),
              const Spacer(),
              IconButton(onPressed: () => _adjust(r), icon: const Icon(Icons.edit_outlined), tooltip: 'Ajustar estoque de ${r.name}'),
            ]),
          ],
        ),
      ),
    );
  }

  Widget _kv(String k, String v) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    mainAxisSize: MainAxisSize.min,
    children: [Text(k, style: TextStyle(color: context.c.muted, fontSize: 11.5)), Text(v, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13.5))],
  );
}

/// Ajuste manual das unidades prontas (vendas ou perdas feitas fora do sistema).
class _AdjustSheet extends StatefulWidget {
  const _AdjustSheet({required this.row});
  final ProductionRow row;
  @override
  State<_AdjustSheet> createState() => _AdjustSheetState();
}

class _AdjustSheetState extends State<_AdjustSheet> {
  late final _qty = TextEditingController(text: fieldNum(widget.row.qty));
  bool _busy = false;

  @override
  void dispose() {
    _qty.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _busy = true);
    try {
      await context.read<Session>().ownerApi.patch('/api/production/stock/${widget.row.id}', {'qty': _qty.text});
      Toast.ok('Estoque de produção atualizado');
      if (mounted) Navigator.pop(context, true);
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Column(children: [
    Text('Use para corrigir a contagem quando vender ou perder unidades fora do sistema. Isso não mexe nos ingredientes.', style: TextStyle(color: context.c.muted)),
    const SizedBox(height: 14),
    NumberField(label: 'Unidades prontas de ${widget.row.name}', controller: _qty),
    const SizedBox(height: 16),
    BusyButton(label: 'Salvar', busy: _busy, onPressed: _save),
  ]);
}

/// Registrar produção em duas etapas: receita e quantidade, depois a conferência dos ingredientes que serão descontados.
class ProduceSheet extends StatefulWidget {
  const ProduceSheet({super.key, required this.rows, this.initial});
  final List<ProductionRow> rows;
  final int? initial;
  @override
  State<ProduceSheet> createState() => _ProduceSheetState();
}

class _ProduceSheetState extends State<ProduceSheet> {
  late int? _recipe = widget.initial;
  final _qty = TextEditingController();
  Map<String, dynamic>? _review;
  bool _force = false, _busy = false;

  @override
  void dispose() {
    _qty.dispose();
    super.dispose();
  }

  ApiClient get _api => context.read<Session>().ownerApi;

  Future<void> _next() async {
    final n = parseNum(_qty.text);
    if (_recipe == null) return Toast.error('Escolha a receita.');
    if (n <= 0 || n != n.roundToDouble()) return Toast.error('Informe quantas unidades foram produzidas (número inteiro maior que zero).');
    setState(() => _busy = true);
    try {
      final r = await _api.post('/api/production/preview', {'recipe_id': _recipe, 'qty': n.toInt()});
      if (mounted) setState(() { _review = Map<String, dynamic>.from(r as Map); _force = false; });
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _confirm() async {
    if (_busy) return; // trava o duplo toque
    final r = _review!;
    setState(() => _busy = true);
    try {
      await _api.post('/api/production/runs', {'recipe_id': r['recipe_id'], 'qty': r['qty'], 'force': _force});
      Toast.ok('Produção registrada');
      if (mounted) Navigator.pop(context, true);
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => _review == null ? _form(context) : _confirmStep(context, _review!);

  Widget _form(BuildContext context) => Column(children: [
    SelectField<int?>(
      label: 'Receita',
      value: _recipe,
      items: [
        const DropdownMenuItem(value: null, child: Text('Escolha a receita')),
        for (final r in widget.rows) DropdownMenuItem(value: r.id, child: Text(r.name, overflow: TextOverflow.ellipsis)),
      ],
      onChanged: (v) => setState(() => _recipe = v),
    ),
    const SizedBox(height: 14),
    NumberField(label: 'Unidades produzidas', controller: _qty, integer: true, helper: 'Ex.: 24. Os ingredientes são calculados pelo rendimento da receita.'),
    const SizedBox(height: 16),
    BusyButton(label: 'Revisar', busy: _busy, onPressed: _next),
  ]);

  Widget _confirmStep(BuildContext context, Map<String, dynamic> r) {
    final c = context.c;
    final short = r['short'] == true;
    final lines = [for (final l in r['lines'] as List) Map<String, dynamic>.from(l as Map)];
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Container(
        width: double.infinity,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: c.pinkTint, borderRadius: BorderRadius.circular(16)),
        child: Text.rich(TextSpan(children: [
          TextSpan(text: '${number(_n(r['qty']))} × ${r['recipe']}\n', style: TextStyle(fontWeight: FontWeight.w800, color: c.pinkStrong)),
          TextSpan(text: 'Custo dos ingredientes: ${money(_n(r['total_cost']))}', style: TextStyle(color: c.pinkStrong)),
        ])),
      ),
      if (short)
        Container(
          width: double.infinity,
          margin: const EdgeInsets.only(top: 12),
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(color: c.bad.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(16), border: Border.all(color: c.bad.withValues(alpha: 0.4))),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Estoque de ingredientes insuficiente', style: TextStyle(color: c.bad, fontWeight: FontWeight.w800)),
            for (final l in lines.where((l) => _n(l['missing']) > 0))
              Text('• ${l['name']}: faltam ${number(_n(l['missing']))} ${l['unit']}', style: TextStyle(color: c.bad)),
            InkWell(
              onTap: () => setState(() => _force = !_force),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Checkbox(value: _force, onChanged: (v) => setState(() => _force = v ?? false)),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text('Produzir mesmo assim (meu estoque está desatualizado). O estoque do ingrediente para em zero.', style: TextStyle(color: c.bad, fontWeight: FontWeight.w700, fontSize: 13.5)),
                  ),
                ),
              ]),
            ),
          ]),
        ),
      const SizedBox(height: 12),
      if (lines.isEmpty)
        Text('Esta receita não tem ingredientes.', style: TextStyle(color: c.muted))
      else
        for (final l in lines)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 5),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('${l['name']}', style: const TextStyle(fontWeight: FontWeight.w700)),
                  Text(
                    'Estoque: ${number(_n(l['stock']))} → ${number(_n(l['after']))} ${l['unit']}',
                    style: TextStyle(fontSize: 12.5, color: _n(l['missing']) > 0 ? c.bad : c.muted),
                  ),
                ]),
              ),
              Text('−${number(_n(l['needed']))} ${l['unit']}', style: const TextStyle(fontWeight: FontWeight.w700)),
            ]),
          ),
      const SizedBox(height: 16),
      Row(children: [
        Expanded(child: BusyButton(label: 'Voltar', outlined: true, onPressed: _busy ? null : () => setState(() => _review = null))),
        const SizedBox(width: 12),
        Expanded(child: BusyButton(label: 'Confirmar produção', busy: _busy, onPressed: short && !_force ? null : _confirm)),
      ]),
    ]);
  }
}

/// Histórico de produção: filtro por receita e período, e "Desfazer".
class _HistoryView extends StatefulWidget {
  const _HistoryView({super.key, required this.rows, required this.initial, required this.onUndo});
  final List<ProductionRow> rows;
  final List<Map<String, dynamic>> initial;
  final Future<void> Function(Map<String, dynamic> run) onUndo;
  @override
  State<_HistoryView> createState() => _HistoryViewState();
}

class _HistoryViewState extends State<_HistoryView> {
  late List<Map<String, dynamic>> _runs = widget.initial;
  int? _recipe;
  String _from = '', _to = '';
  int? _open;

  Future<void> _load() async {
    final q = [if (_recipe != null) 'recipe=$_recipe', if (_from.isNotEmpty) 'from=$_from', if (_to.isNotEmpty) 'to=$_to'].join('&');
    try {
      final r = await context.read<Session>().ownerApi.get('/api/production/runs${q.isEmpty ? '' : '?$q'}');
      if (mounted) setState(() => _runs = [for (final e in r as List) Map<String, dynamic>.from(e as Map)]);
    } on ApiException catch (e) {
      Toast.error(e.message);
    }
  }

  Future<void> _pick(bool from) async {
    final now = DateTime.now();
    final d = await showDatePicker(
      context: context,
      initialDate: now,
      firstDate: DateTime(now.year - 3),
      lastDate: now,
      cancelText: 'Limpar',
      confirmText: 'Escolher',
    );
    setState(() => from ? _from = d == null ? '' : todayIso(d) : _to = d == null ? '' : todayIso(d));
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      SelectField<int?>(
        label: 'Receita',
        value: _recipe,
        items: [const DropdownMenuItem(value: null, child: Text('Todas as receitas')), for (final r in widget.rows) DropdownMenuItem(value: r.id, child: Text(r.name, overflow: TextOverflow.ellipsis))],
        onChanged: (v) {
          setState(() => _recipe = v);
          _load();
        },
      ),
      const SizedBox(height: 8),
      Row(children: [
        Expanded(child: OutlinedButton(onPressed: () => _pick(true), child: Text(_from.isEmpty ? 'De' : 'De ${dateBR(_from)}'))),
        const SizedBox(width: 8),
        Expanded(child: OutlinedButton(onPressed: () => _pick(false), child: Text(_to.isEmpty ? 'Até' : 'Até ${dateBR(_to)}'))),
      ]),
      const SizedBox(height: 10),
      if (_runs.isEmpty)
        Padding(padding: const EdgeInsets.all(24), child: Center(child: Text('Nenhuma produção registrada.', style: TextStyle(color: c.muted))))
      else
        for (final r in _runs) _run(r),
    ]);
  }

  Widget _run(Map<String, dynamic> r) {
    final c = context.c;
    final undone = r['undone_at'] != null;
    final id = (r['id'] as num).toInt();
    final at = DateTime.tryParse('${r['created_at']}')?.toLocal();
    return Opacity(
      opacity: undone ? 0.6 : 1,
      child: Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: AppCard(
          padding: const EdgeInsets.fromLTRB(14, 12, 6, 8),
          onTap: () => setState(() => _open = _open == id ? null : id),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('${number(_n(r['qty']))} × ${r['recipe']}', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15.5)),
                  Text('${at == null ? '' : DateFormat('dd/MM/yyyy HH:mm').format(at)} · custo ${money(_n(r['total_cost']))}', style: TextStyle(color: c.muted, fontSize: 12.5)),
                ]),
              ),
              if (undone) const AppBadge('Desfeita', tone: 'neutral') else if (r['forced'] == true) const AppBadge('Com falta', tone: 'warn'),
              const SizedBox(width: 8),
            ]),
            if (_open == id)
              for (final l in (r['consumed'] as List? ?? []))
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text('${(l as Map)['name']}: ${number(_n(l['needed']))} ${l['unit']} · ${money(_n(l['cost']))}', style: TextStyle(color: c.muted, fontSize: 13)),
                ),
            if (!undone)
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(onPressed: () async { await widget.onUndo(r); }, icon: const Icon(Icons.undo_rounded, size: 18), label: const Text('Desfazer')),
              ),
          ]),
        ),
      ),
    );
  }
}
