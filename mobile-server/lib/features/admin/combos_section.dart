import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/combos.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

/// Seção Combos da página do Cardápio: lista, desconto sugerido e o editor ("+ Novo combo"). Toda rota de
/// /api/combos devolve a página inteira, que substitui a atual.
class CombosSection extends StatefulWidget {
  const CombosSection({super.key});
  @override
  State<CombosSection> createState() => _CombosSectionState();
}

class _CombosSectionState extends State<CombosSection> {
  Map<String, dynamic>? _page;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final p = await context.read<Session>().ownerApi.get('/api/combos');
      if (mounted) setState(() => _page = Map<String, dynamic>.from(p as Map));
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  List<Map<String, dynamic>> _list(String key) => [for (final e in (_page?[key] as List? ?? const [])) Map<String, dynamic>.from(e as Map)];

  Future<void> _open([Map<String, dynamic>? combo]) async {
    final page = await Navigator.of(context).push<Map<String, dynamic>>(MaterialPageRoute(
      fullscreenDialog: true,
      builder: (_) => ComboEditorScreen(
        products: _list('products'),
        discountPct: ((_page?['discount_pct'] as num?) ?? 10).toDouble(),
        minMarginPct: ((_page?['min_margin_pct'] as num?) ?? 30).toDouble(),
        combo: combo,
      ),
    ));
    if (page != null && mounted) setState(() => _page = page);
  }

  Future<void> _editDiscount() async {
    final ctl = TextEditingController(text: fieldNum(((_page?['discount_pct'] as num?) ?? 10).toDouble()));
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Desconto sugerido dos combos'),
        content: NumberField(label: 'Desconto (%)', controller: ctl, helper: 'Sobre a soma dos produtos avulsos. De 0% a 90%.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancelar')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Salvar')),
        ],
      ),
    );
    final pct = parseNum(ctl.text);
    ctl.dispose();
    if (ok != true || !mounted) return;
    try {
      final p = await context.read<Session>().ownerApi.put('/api/combos/settings', {'discount_pct': pct});
      if (mounted) setState(() => _page = Map<String, dynamic>.from(p as Map));
    } on ApiException catch (e) {
      Toast.error(e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final combos = _list('combos');
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const SectionTitle('Combos'),
      if (_error != null)
        Text(_error!, style: TextStyle(color: c.bad))
      else if (_page == null)
        const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator()))
      else ...[
        Text(
          'Junte produtos num combo. O preço é sugerido sozinho (soma dos produtos com o desconto abaixo, nunca abaixo do custo + sua margem mínima), e você pode escolher outro.',
          style: TextStyle(color: c.muted),
        ),
        const SizedBox(height: 8),
        Row(children: [
          Expanded(child: Text('Desconto sugerido: ${fieldNum(((_page?['discount_pct'] as num?) ?? 10).toDouble())}%', style: const TextStyle(fontWeight: FontWeight.w700))),
          TextButton(onPressed: _editDiscount, child: const Text('Alterar')),
        ]),
        const SizedBox(height: 8),
        for (final combo in combos)
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: _ComboCard(combo, onTap: () => _open(combo)),
          ),
        BusyButton(
          key: const Key('combo_new'),
          label: '+ Novo combo',
          busy: false,
          onPressed: () => _list('products').where((p) => p['on_menu'] == true).length < 1
              ? Toast.warn('Coloque produtos no cardápio antes de montar um combo.')
              : _open(),
        ),
      ],
    ]);
  }
}

class _ComboCard extends StatelessWidget {
  const _ComboCard(this.combo, {required this.onTap});
  final Map<String, dynamic> combo;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final items = [for (final e in combo['items'] as List) Map<String, dynamic>.from(e as Map)];
    final fixed = combo['price'] != null;
    final available = combo['available'] == true;
    double n(String k) => ((combo[k] as num?) ?? 0).toDouble();
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(18),
      child: AppCard(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: Text('${combo['name']}', style: const TextStyle(fontWeight: FontWeight.w800))),
            Text(money(n('final_price')), style: display(context, size: 20)),
          ]),
          Text(items.map((i) => '${i['qty']} ${i['name']}').join(' + '), style: TextStyle(color: c.muted)),
          const SizedBox(height: 4),
          Text(
            '${fixed ? 'Preço escolhido' : 'Preço sugerido'} · avulso ${money(n('full_price'))} · lucro ${money(n('profit'))} (${fieldNum(n('margin_pct'))}%)',
            style: TextStyle(color: c.muted, fontSize: 12.5),
          ),
          if (combo['below_cost'] == true)
            Text('O preço não cobre o custo dos produtos (${money(n('cost'))}).', style: TextStyle(color: c.bad, fontSize: 12.5, fontWeight: FontWeight.w700)),
          if (!available)
            Text('Fora do cardápio: ${combo['unavailable_reason'] ?? ''}', style: TextStyle(color: c.warn, fontSize: 12.5)),
        ]),
      ),
    );
  }
}

/// Editor de combo: nome, produtos com quantidade, preço (sugerido ao vivo ou escolhido) e se aparece no cardápio.
/// Devolve a página atualizada ao fechar (pop), ou nada se cancelou.
class ComboEditorScreen extends StatefulWidget {
  const ComboEditorScreen({super.key, required this.products, required this.discountPct, required this.minMarginPct, this.combo});
  final List<Map<String, dynamic>> products;
  final double discountPct, minMarginPct;
  final Map<String, dynamic>? combo;
  @override
  State<ComboEditorScreen> createState() => _ComboEditorScreenState();
}

class _ComboEditorScreenState extends State<ComboEditorScreen> {
  late final _name = TextEditingController(text: widget.combo?['name'] as String? ?? '');
  late final _price = TextEditingController(text: widget.combo?['price'] == null ? '' : fieldNum((widget.combo!['price'] as num).toDouble()));
  late bool _show = widget.combo?['show_on_menu'] != false;
  late final Map<int, int> _qty = {
    for (final e in (widget.combo?['items'] as List? ?? const [])) ((e as Map)['recipe_id'] as num).toInt(): (e['qty'] as num).toInt(),
  };
  bool _busy = false;

  @override
  void dispose() {
    _name.dispose();
    _price.dispose();
    super.dispose();
  }

  Map<String, dynamic>? _product(int id) => widget.products.where((p) => (p['id'] as num).toInt() == id).firstOrNull;

  ComboCalc get _calc => comboCalc(
        [
          for (final e in _qty.entries)
            if (_product(e.key) != null)
              ComboPart(qty: e.value, price: ((_product(e.key)!['price'] as num?) ?? 0).toDouble(), unitCost: ((_product(e.key)!['cost'] as num?) ?? 0).toDouble()),
        ],
        discountPct: widget.discountPct,
        minMarginPct: widget.minMarginPct,
        fixedPrice: parseNum(_price.text) > 0 ? parseNum(_price.text) : null,
      );

  void _step(int id, int delta) => setState(() {
        final n = (_qty[id] ?? 0) + delta;
        if (n <= 0) {
          _qty.remove(id);
        } else {
          _qty[id] = n > 99 ? 99 : n;
        }
      });

  Map<String, dynamic> get _body => {
        'name': _name.text.trim(),
        'price': parseNum(_price.text) > 0 ? parseNum(_price.text) : null,
        'show_on_menu': _show,
        'items': [for (final e in _qty.entries) {'recipe_id': e.key, 'qty': e.value}],
      };

  Future<void> _save() async {
    if (_name.text.trim().isEmpty) return Toast.error('Dê um nome para o combo.');
    if (_qty.values.fold<int>(0, (s, q) => s + q) < 2) return Toast.error('Escolha pelo menos 2 unidades de produtos.');
    final api = context.read<Session>().ownerApi;
    setState(() => _busy = true);
    try {
      final id = widget.combo?['id'];
      final page = id == null ? await api.post('/api/combos', _body) : await api.put('/api/combos/$id', _body);
      Toast.ok(id == null ? 'Combo criado' : 'Combo salvo');
      if (mounted) Navigator.of(context).pop(Map<String, dynamic>.from(page as Map));
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _delete() async {
    if (!await confirmDialog(context, title: 'Excluir “${widget.combo!['name']}”?', message: 'O combo sai do cardápio. Pedidos antigos continuam salvos.')) return;
    if (!mounted) return;
    try {
      final page = await context.read<Session>().ownerApi.delete('/api/combos/${widget.combo!['id']}');
      Toast.ok('Combo excluído');
      if (mounted) Navigator.of(context).pop(Map<String, dynamic>.from(page as Map));
    } on ApiException catch (e) {
      Toast.error(e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final calc = _calc;
    final fixed = parseNum(_price.text) > 0;
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.combo == null ? 'Novo combo' : 'Combo'),
        actions: [
          if (widget.combo != null) IconButton(tooltip: 'Excluir combo', onPressed: _delete, icon: const Icon(Icons.delete_outline_rounded)),
        ],
      ),
      body: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 24), children: [
        AppTextField(key: const Key('combo_name'), label: 'Nome do combo', controller: _name, hint: 'Ex.: Combo Festa', maxLength: 60, onChanged: (_) => setState(() {})),
        const SectionTitle('Produtos'),
        for (final p in widget.products)
          Builder(builder: (context) {
            final id = (p['id'] as num).toInt();
            final n = _qty[id] ?? 0;
            final onMenu = p['on_menu'] == true;
            return Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: AppCard(
                child: Row(children: [
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text('${p['name']}', style: const TextStyle(fontWeight: FontWeight.w700)),
                      Text(
                        onMenu ? '${money(((p['price'] as num?) ?? 0).toDouble())} cada · custo ${money(((p['cost'] as num?) ?? 0).toDouble())}' : 'Fora do cardápio',
                        style: TextStyle(color: onMenu ? c.muted : c.warn, fontSize: 12.5),
                      ),
                    ]),
                  ),
                  if (n > 0) ...[
                    IconButton.outlined(onPressed: () => _step(id, -1), icon: const Icon(Icons.remove_rounded), tooltip: 'Menos ${p['name']}'),
                    SizedBox(width: 28, child: Text('$n', textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16))),
                  ],
                  IconButton.filled(key: Key('combo_add_$id'), onPressed: () => _step(id, 1), icon: const Icon(Icons.add_rounded), tooltip: 'Mais ${p['name']}'),
                ]),
              ),
            );
          }),
        const SectionTitle('Preço'),
        AppCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Avulso: ${money(calc.fullPrice)} · Custo: ${money(calc.cost)}', style: TextStyle(color: c.muted)),
            Text('Sugerido: ${money(calc.suggested)} (${fieldNum(widget.discountPct)}% de desconto)', key: const Key('combo_suggested'), style: const TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: 12),
            NumberField(
              label: 'Preço do combo (R\$, opcional)',
              controller: _price,
              hint: calc.suggested > 0 ? fieldNum(calc.suggested) : '',
              helper: fixed ? 'Escolhido por você.' : 'Em branco usa o sugerido (e acompanha os preços dos produtos).',
              onChanged: (_) => setState(() {}),
            ),
            const SizedBox(height: 8),
            Text(
              'Lucro: ${money(calc.profit)} (${fieldNum(calc.marginPct)}% sobre o custo) · o cliente economiza ${money(calc.savings)}',
              style: TextStyle(color: calc.belowCost ? c.bad : c.ink, fontWeight: FontWeight.w700),
            ),
            if (calc.belowCost) Text('Esse preço não cobre o custo dos produtos.', style: TextStyle(color: c.bad, fontSize: 12.5)),
          ]),
        ),
        const SizedBox(height: 8),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Mostrar na landing page'),
          subtitle: Text('Só aparece se todos os produtos estiverem no cardápio.', style: TextStyle(color: c.muted, fontSize: 12.5)),
          value: _show,
          onChanged: (v) => setState(() => _show = v),
        ),
        const SizedBox(height: 12),
        BusyButton(key: const Key('combo_save'), label: 'Salvar combo', busy: _busy, onPressed: _save),
      ]),
    );
  }
}
