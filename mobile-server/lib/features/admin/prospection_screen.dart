import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/prospection.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/share_menu.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

import '../../core/prospection_store.dart';
import 'prospection_table_screen.dart';

/// Um produto do cadastro (receita) como a Prospecção o enxerga: só custo por unidade e preço de venda.
class _Product {
  const _Product(this.id, this.name, this.category, this.cost, this.price);
  final int id;
  final String name;
  final String? category;
  final double cost, price;
  String get itemId => 'r-$id';
  ProspectItem toItem() => ProspectItem(id: itemId, name: name, cost: cost, price: price);
}

/// Prospecção: planeja o preço de produtos por quantidade (desconto, margem, frete, impostos), gera a tabela para print e exporta CSV.
/// O estado fica na [ProspectionStore] (acima das telas): sair e voltar não perde nada. Nada aqui altera o cadastro.
class ProspectionScreen extends StatelessWidget {
  const ProspectionScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    return Scaffold(
      appBar: AppBar(title: const Text('Prospecção')),
      body: Loader<List<_Product>>(
        scroll: false, // a própria tela rola (ListView)
        load: () async {
          final r = await Future.wait([api.get('/api/recipes'), api.get('/api/categories?kind=recipe')]);
          final cats = {for (final e in r[1] as List) Category.fromJson(e).id: Category.fromJson(e).name};
          return [
            for (final e in r[0] as List)
              () {
                final rec = RecipeRow.fromJson(e);
                return _Product(rec.id, rec.name, cats[rec.categoryId], rec.pricing?.perUnit ?? 0, rec.pricing?.rounded ?? 0);
              }(),
          ];
        },
        builder: (context, products, reload) => _ProspectionView(products: products),
      ),
    );
  }
}

/// Campo numérico com texto próprio: digitar "1," ou "0,0" não faz o valor pular. Vazio vale 0 (ou null, se [nullable]).
class _Num extends StatefulWidget {
  const _Num({super.key, required this.label, required this.value, required this.onChanged, this.suffix, this.nullable = false, this.required = false, this.hint});
  final String label;
  final double? value;
  final ValueChanged<double?> onChanged;
  final String? suffix, hint;
  final bool nullable, required;
  @override
  State<_Num> createState() => _NumState();
}

class _NumState extends State<_Num> {
  late final _c = TextEditingController(text: _text(widget.value));
  static String _text(double? v) => v == null || v == 0 ? (v == 0 ? '0' : '') : fieldNum(v);

  @override
  void didUpdateWidget(_Num old) {
    super.didUpdateWidget(old);
    // valor mudou por fora (abrir outra simulação): atualiza o texto; mudanças vindas da digitação não mexem no cursor
    if ((parseLocaleNumber(_c.text) ?? 0) != (widget.value ?? 0) || (widget.value == null && _c.text.isNotEmpty)) _c.text = _text(widget.value);
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final blank = _c.text.trim().isEmpty;
    return NumberField(
      label: widget.label,
      controller: _c,
      suffixText: widget.suffix,
      hint: widget.hint,
      helper: widget.required && blank ? 'Obrigatório' : null,
      onChanged: (s) {
        setState(() {});
        widget.onChanged(s.trim().isEmpty ? (widget.nullable ? null : 0) : (parseLocaleNumber(s) ?? 0));
      },
    );
  }
}

class _ProspectionView extends StatefulWidget {
  const _ProspectionView({required this.products});
  final List<_Product> products;
  @override
  State<_ProspectionView> createState() => _ProspectionViewState();
}

class _ProspectionViewState extends State<_ProspectionView> {
  final _search = TextEditingController();
  final _mName = TextEditingController(), _mCost = TextEditingController(), _mPrice = TextEditingController();
  late final _simName = TextEditingController(text: context.read<ProspectionStore>().name);
  String _cat = '';
  String _q = '';

  @override
  void dispose() {
    for (final c in [_search, _mName, _mCost, _mPrice, _simName]) {
      c.dispose();
    }
    super.dispose();
  }

  List<_Product> get _filtered {
    final t = _q.trim().toLowerCase();
    return [for (final p in widget.products) if ((_cat.isEmpty || p.category == _cat) && (t.isEmpty || p.name.toLowerCase().contains(t))) p];
  }

  void _addManual(ProspectionStore s) {
    final name = _mName.text.trim();
    if (name.isEmpty) return Toast.error('Dê um nome ao item.');
    if (_mCost.text.trim().isEmpty || _mPrice.text.trim().isEmpty) return Toast.error('Informe o custo e o preço de venda.');
    s.add(ProspectItem(id: ProspectionStore.newManualId(), name: name, cost: parseLocaleNumber(_mCost.text) ?? 0, price: parseLocaleNumber(_mPrice.text) ?? 0));
    _mName.clear();
    _mCost.clear();
    _mPrice.clear();
  }

  Future<void> _csv(ProspectionStore s) async {
    if (s.items.isEmpty) return Toast.error('Escolha ao menos um produto.');
    final r = s.result;
    final name = csvFileName(todayIso());
    try {
      await shareFile(csvBytes(toCsv(r.rows, r.totals)), name: name, mimeType: 'text/csv', text: name);
    } catch (_) {
      Toast.error('Não foi possível abrir o compartilhamento.');
    }
  }

  void _table(ProspectionStore s) {
    if (s.items.isEmpty) return Toast.error('Escolha ao menos um produto.');
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => ProspectionTableScreen(title: s.name.trim().isEmpty ? 'Prospecção de preços' : s.name.trim(), result: s.result, onCsv: () => _csv(s)),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final s = context.watch<ProspectionStore>();
    final c = context.c;
    final g = s.globals;
    final res = s.result;
    final cats = {for (final p in widget.products) if (p.category != null) p.category!}.toList()..sort();
    final filtered = _filtered;

    Widget gap([double h = 12]) => SizedBox(height: h);
    Widget row(List<Widget> kids) => Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      for (var i = 0; i < kids.length; i++) ...[if (i > 0) const SizedBox(width: 10), Expanded(child: kids[i])],
    ]);

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 40),
      children: [
        const PageTitle('Prospecção', subtitle: 'Escolha os produtos, defina a quantidade e veja preço, desconto, lucro e margem na hora.'),

        // ── 1. parâmetros gerais
        AppCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Parâmetros gerais', style: display(context, size: 22)),
            Text('Valem para todos os itens; cada item pode sobrescrever o desconto e o imposto.', style: TextStyle(color: c.muted, fontSize: 13)),
            gap(),
            row([
              SelectField<DiscountMode>(
                label: 'Desconto geral',
                value: g.discountMode,
                items: const [DropdownMenuItem(value: DiscountMode.pct, child: Text('Em %')), DropdownMenuItem(value: DiscountMode.brl, child: Text('Em R\$'))],
                onChanged: (v) => s.setGlobals(g.copyWith(discountMode: v)),
              ),
              _Num(label: 'Valor', value: g.discount, suffix: g.discountMode == DiscountMode.pct ? '%' : r'R$', onChanged: (v) => s.setGlobals(g.copyWith(discount: v ?? 0))),
            ]),
            gap(),
            row([
              _Num(label: 'Impostos / taxas (sobre o bruto)', value: g.taxPct, suffix: '%', onChanged: (v) => s.setGlobals(g.copyWith(taxPct: v ?? 0))),
              _Num(label: 'Frete total (rateado)', value: g.freight, suffix: r'R$', onChanged: (v) => s.setGlobals(g.copyWith(freight: v ?? 0))),
            ]),
            gap(),
            row([
              _Num(label: 'Markup alvo (sobre o custo)', value: g.targetMarkupPct, suffix: '%', onChanged: (v) => s.setGlobals(g.copyWith(targetMarkupPct: v ?? 0))),
              _Num(label: 'Margem mínima (alerta)', value: g.minMarginPct, suffix: '%', onChanged: (v) => s.setGlobals(g.copyWith(minMarginPct: v ?? 0))),
            ]),
            Divider(height: 28, color: c.line),
            Row(children: [
              Expanded(child: Text('Desconto por quantidade', style: const TextStyle(fontWeight: FontWeight.w700))),
              TextButton.icon(
                onPressed: () => s.setGlobals(g.copyWith(tiers: [...g.tiers, const Tier(0, 0)])),
                icon: const Icon(Icons.add_rounded, size: 18),
                label: const Text('Faixa'),
              ),
            ]),
            Text('Ex.: 10+ un = 5%, 50+ un = 10%. Vale para os itens em que você marcar “usar faixas”.', style: TextStyle(color: c.muted, fontSize: 12)),
            for (var i = 0; i < g.tiers.length; i++) ...[
              gap(8),
              Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Expanded(child: _Num(key: ValueKey('tq$i'), label: 'A partir de', value: g.tiers[i].minQty, suffix: 'un', onChanged: (v) => s.setGlobals(g.copyWith(tiers: [for (var k = 0; k < g.tiers.length; k++) k == i ? Tier(v ?? 0, g.tiers[k].pct) : g.tiers[k]])))),
                const SizedBox(width: 10),
                Expanded(child: _Num(key: ValueKey('tp$i'), label: 'Desconto', value: g.tiers[i].pct, suffix: '%', onChanged: (v) => s.setGlobals(g.copyWith(tiers: [for (var k = 0; k < g.tiers.length; k++) k == i ? Tier(g.tiers[k].minQty, v ?? 0) : g.tiers[k]])))),
                Padding(
                  padding: const EdgeInsets.only(top: 22),
                  child: IconButton(tooltip: 'Remover faixa', onPressed: () => s.setGlobals(g.copyWith(tiers: [...g.tiers]..removeAt(i))), icon: Icon(Icons.close_rounded, color: c.muted)),
                ),
              ]),
            ],
          ]),
        ),

        // ── 2. produtos
        SectionTitle('Produtos', action: Text('${s.items.length} selecionado${s.items.length == 1 ? '' : 's'}', style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w700))),
        AppCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Marque os produtos da simulação. Mudar custo ou preço aqui não altera o cadastro.', style: TextStyle(color: c.muted, fontSize: 13)),
            gap(),
            AppTextField(label: 'Buscar produto', controller: _search, hint: 'Nome do produto', onChanged: (v) => setState(() => _q = v)),
            if (cats.isNotEmpty) ...[
              gap(),
              SelectField<String>(
                label: 'Categoria',
                value: cats.contains(_cat) ? _cat : '',
                items: [const DropdownMenuItem(value: '', child: Text('Todas')), for (final k in cats) DropdownMenuItem(value: k, child: Text(k))],
                onChanged: (v) => setState(() => _cat = v ?? ''),
              ),
            ],
            gap(),
            Row(children: [
              Expanded(child: BusyButton(label: 'Selecionar todos', icon: Icons.done_all_rounded, outlined: true, onPressed: filtered.isEmpty ? null : () => s.addAll(filtered.map((p) => p.toItem())))),
              const SizedBox(width: 10),
              Expanded(child: BusyButton(label: 'Limpar seleção', icon: Icons.clear_all_rounded, outlined: true, onPressed: s.items.isEmpty ? null : s.clearItems)),
            ]),
            gap(),
            if (widget.products.isEmpty)
              Text('Nenhuma receita cadastrada ainda. Use o item manual abaixo.', style: TextStyle(color: c.muted))
            else if (filtered.isEmpty)
              Text('Nada encontrado.', style: TextStyle(color: c.muted))
            else
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 320),
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: filtered.length,
                  itemBuilder: (_, i) {
                    final p = filtered[i];
                    return CheckboxListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      controlAffinity: ListTileControlAffinity.leading,
                      activeColor: c.pink,
                      value: s.has(p.itemId),
                      onChanged: (on) => on == true ? s.add(p.toItem()) : s.remove(p.itemId),
                      title: Text(p.name, style: const TextStyle(fontWeight: FontWeight.w600)),
                      subtitle: Text('Custo ${money(p.cost)} · Preço ${p.price > 0 ? money(p.price) : 'sem preço'}', style: TextStyle(color: c.muted, fontSize: 12)),
                    );
                  },
                ),
              ),
            Divider(height: 28, color: c.line),
            const Text('Item manual (fora do cadastro)', style: TextStyle(fontWeight: FontWeight.w700)),
            gap(8),
            AppTextField(label: 'Nome', controller: _mName, maxLength: 80, capitalization: TextCapitalization.sentences),
            gap(8),
            row([NumberField(label: r'Custo unit. (R$)', controller: _mCost), NumberField(label: r'Preço de venda (R$)', controller: _mPrice)]),
            gap(),
            BusyButton(label: 'Adicionar item', icon: Icons.add_rounded, onPressed: () => _addManual(s)),
          ]),
        ),

        // ── 3. itens
        if (s.items.isNotEmpty) ...[
          const SectionTitle('Itens da simulação'),
          for (final it in s.items)
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: _ItemCard(
                key: ValueKey(it.id),
                item: it,
                row: res.rows.firstWhere((r) => r.id == it.id),
                hasTiers: g.tiers.isNotEmpty,
                onChanged: s.update,
                onRemove: () => s.remove(it.id),
              ),
            ),
        ],

        // ── 4. totais e saída
        const SectionTitle('Totais'),
        AppCard(
          borderColor: s.items.isEmpty || res.totals.level == Level.ok ? null : (res.totals.level == Level.negative ? c.bad : c.warn),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            if (s.items.isNotEmpty && res.totals.level != Level.ok) ...[
              Row(children: [
                Icon(Icons.warning_amber_rounded, color: res.totals.level == Level.negative ? c.bad : c.warn, size: 20),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    res.totals.level == Level.negative ? 'A simulação dá prejuízo.' : 'A margem total está abaixo do mínimo de ${percent2(g.minMarginPct)}.',
                    style: TextStyle(fontWeight: FontWeight.w700, color: res.totals.level == Level.negative ? c.bad : c.warn),
                  ),
                ),
              ]),
              gap(),
            ],
            Wrap(spacing: 16, runSpacing: 12, children: [
              _Stat('Total bruto', money(res.totals.gross)),
              _Stat('Total de desconto', money(res.totals.discountValue), sub: percent2(res.totals.discountPct)),
              _Stat('Total líquido', money(res.totals.net), strong: true),
              _Stat('Custo total', money(res.totals.costTotal + res.totals.extra + res.totals.freight), sub: res.totals.tax > 0 ? '+ impostos ${money(res.totals.tax)}' : null),
              _Stat('Lucro', money(res.totals.profit), strong: true, color: s.items.isEmpty ? null : _levelColor(c, res.totals.level)),
              _Stat('Margem (venda)', percent2(res.totals.marginPct), color: s.items.isEmpty ? null : _levelColor(c, res.totals.level)),
              _Stat('Markup (custo)', percent2(res.totals.markupPct)),
              _Stat('Quantidade', number(res.totals.qty)),
            ]),
            gap(18),
            BusyButton(label: 'Gerar tabela final', icon: Icons.table_chart_outlined, onPressed: () => _table(s)),
            gap(8),
            BusyButton(label: 'Exportar CSV', icon: Icons.ios_share_rounded, outlined: true, onPressed: () => _csv(s)),
          ]),
        ),

        // ── 5. simulações salvas
        const SectionTitle('Simulações salvas'),
        AppCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Ficam guardadas neste aparelho.', style: TextStyle(color: c.muted, fontSize: 13)),
            gap(),
            AppTextField(label: 'Nome da simulação', controller: _simName, hint: 'Ex.: Festa da Ana, 100 docinhos', maxLength: 60, onChanged: s.setName),
            gap(),
            BusyButton(
              label: s.savedId == null ? 'Salvar' : 'Atualizar',
              icon: Icons.save_outlined,
              onPressed: () => s.save() ? Toast.ok(s.savedId == null ? 'Simulação salva.' : 'Simulação salva.') : Toast.error('Dê um nome à simulação para salvar.'),
            ),
            if (s.savedId != null) ...[
              gap(8),
              BusyButton(label: 'Salvar como cópia', icon: Icons.copy_rounded, outlined: true, onPressed: () {
                s.save(asCopy: true);
                _simName.text = s.name;
                Toast.ok('Cópia salva.');
              }),
            ],
            gap(8),
            BusyButton(label: 'Nova simulação', icon: Icons.add_rounded, outlined: true, onPressed: () {
              s.startNew();
              _simName.clear();
            }),
            for (final sim in s.simulations) ...[
              Divider(height: 24, color: c.line),
              Row(children: [
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(sim.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontWeight: s.savedId == sim.id ? FontWeight.w800 : FontWeight.w600)),
                    Text('${sim.items.length} ite${sim.items.length == 1 ? 'm' : 'ns'}', style: TextStyle(color: c.muted, fontSize: 12)),
                  ]),
                ),
                IconButton(tooltip: 'Abrir', icon: const Icon(Icons.folder_open_rounded), onPressed: () {
                  s.open(sim);
                  _simName.text = sim.name;
                  Toast.ok('“${sim.name}” aberta.');
                }),
                IconButton(tooltip: 'Duplicar', icon: const Icon(Icons.copy_rounded), onPressed: () {
                  s.duplicate(sim);
                  Toast.ok('Simulação duplicada.');
                }),
                IconButton(tooltip: 'Excluir', icon: Icon(Icons.delete_outline_rounded, color: c.bad), onPressed: () => _confirmDelete(s, sim)),
              ]),
            ],
          ]),
        ),
      ],
    );
  }

  Future<void> _confirmDelete(ProspectionStore s, SavedSimulation sim) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Excluir simulação?'),
        content: Text('“${sim.name}” será apagada deste aparelho.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancelar')),
          TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Excluir')),
        ],
      ),
    );
    if (ok == true) s.delete(sim.id);
  }
}

Color _levelColor(AppColors c, Level l) => switch (l) {
  Level.ok => c.ok,
  Level.low => c.warn,
  Level.negative => c.bad,
};

class _Stat extends StatelessWidget {
  const _Stat(this.label, this.value, {this.sub, this.strong = false, this.color});
  final String label, value;
  final String? sub;
  final bool strong;
  final Color? color;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: 148,
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label, style: TextStyle(color: context.c.muted, fontSize: 12)),
      Text(value, style: display(context, size: strong ? 24 : 20, color: color)),
      if (sub != null) Text(sub!, style: TextStyle(color: context.c.muted, fontSize: 12)),
    ]),
  );
}

class _ItemCard extends StatelessWidget {
  const _ItemCard({super.key, required this.item, required this.row, required this.hasTiers, required this.onChanged, required this.onRemove});
  final ProspectItem item;
  final ProspectRow row;
  final bool hasTiers;
  final ValueChanged<ProspectItem> onChanged;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final tone = _levelColor(c, row.level);
    final markup = item.priceMode == PriceMode.markup;
    Widget gap([double h = 10]) => SizedBox(height: h);
    Widget two(Widget a, Widget b) => Row(crossAxisAlignment: CrossAxisAlignment.start, children: [Expanded(child: a), const SizedBox(width: 10), Expanded(child: b)]);
    Widget mini(String k, String v, {bool strong = false, Color? color}) => SizedBox(
      width: 120,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(k, style: TextStyle(color: c.muted, fontSize: 12)),
        Text(v, style: TextStyle(fontWeight: strong ? FontWeight.w800 : FontWeight.w500, color: color)),
      ]),
    );
    final badge = switch (row.level) {
      Level.negative => 'Prejuízo · ${percent2(row.marginPct)}',
      Level.low => 'Abaixo do mínimo · ${percent2(row.marginPct)}',
      Level.ok => 'Margem ${percent2(row.marginPct)}',
    };
    return AppCard(
      borderColor: row.level == Level.ok ? null : tone,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(item.name, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
              Text(item.id.startsWith('m-') ? 'Item manual' : 'Do cadastro (alterações valem só aqui)', style: TextStyle(color: c.muted, fontSize: 12)),
            ]),
          ),
          IconButton(tooltip: 'Remover', onPressed: onRemove, icon: Icon(Icons.delete_outline_rounded, color: c.muted)),
        ]),
        Align(alignment: Alignment.centerLeft, child: AppBadge(badge, tone: switch (row.level) { Level.ok => 'ok', Level.low => 'warn', Level.negative => 'bad' })),
        gap(),
        two(
          _Num(label: 'Quantidade', required: true, value: item.qty, onChanged: (v) => onChanged(item.copyWith(qty: v ?? 0))),
          _Num(label: r'Custo unit. (R$)', required: true, value: item.cost, onChanged: (v) => onChanged(item.copyWith(cost: v ?? 0))),
        ),
        gap(),
        two(
          SelectField<PriceMode>(
            label: 'Preço por',
            value: item.priceMode,
            items: const [DropdownMenuItem(value: PriceMode.price, child: Text('Preço de venda')), DropdownMenuItem(value: PriceMode.markup, child: Text('Markup %'))],
            onChanged: (v) => onChanged(item.copyWith(priceMode: v)),
          ),
          markup
              ? _Num(key: ValueKey('${item.id}-mk'), label: 'Markup', nullable: true, hint: 'alvo geral', value: item.markupPct, suffix: '%', onChanged: (v) => onChanged(item.copyWith(markupPct: v, clearMarkup: v == null)))
              : _Num(key: ValueKey('${item.id}-pr'), label: r'Preço de venda (R$)', required: true, value: item.price, onChanged: (v) => onChanged(item.copyWith(price: v ?? 0))),
        ),
        gap(),
        two(
          SelectField<String>(
            label: 'Desconto',
            value: item.discountMode?.name ?? 'geral',
            items: const [DropdownMenuItem(value: 'geral', child: Text('Usar geral')), DropdownMenuItem(value: 'pct', child: Text('Em %')), DropdownMenuItem(value: 'brl', child: Text('Em R\$ (total)'))],
            onChanged: (v) => onChanged(v == 'geral' ? item.copyWith(clearDiscountMode: true, discount: 0) : item.copyWith(discountMode: v == 'pct' ? DiscountMode.pct : DiscountMode.brl)),
          ),
          item.discountMode == null
              ? const SizedBox.shrink()
              : _Num(key: ValueKey('${item.id}-d'), label: 'Valor', value: item.discount, suffix: item.discountMode == DiscountMode.pct ? '%' : r'R$', onChanged: (v) => onChanged(item.copyWith(discount: v ?? 0))),
        ),
        gap(),
        two(
          _Num(key: ValueKey('${item.id}-t'), label: 'Imposto', nullable: true, hint: 'geral', value: item.taxPct, suffix: '%', onChanged: (v) => onChanged(item.copyWith(taxPct: v, clearTax: v == null))),
          _Num(label: r'Custos extras (R$)', value: item.extra, onChanged: (v) => onChanged(item.copyWith(extra: v ?? 0))),
        ),
        if (hasTiers)
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            activeColor: c.pink,
            value: item.useTiers,
            onChanged: (on) => onChanged(item.copyWith(useTiers: on ?? false)),
            title: Text('Usar faixas por quantidade${item.useTiers && row.discountValue > 0 ? ' (desconto aplicado: ${percent2(row.discountPct)})' : ''}', style: const TextStyle(fontSize: 14)),
          ),
        Divider(height: 24, color: c.line),
        Wrap(spacing: 12, runSpacing: 10, children: [
          mini('Preço unit.', money(row.unitPrice)),
          mini('Preço final unit.', money(row.finalUnit), strong: true),
          mini('Total bruto', money(row.gross)),
          mini('Desconto', '${money(row.discountValue)} · ${percent2(row.discountPct)}'),
          mini('Total líquido', money(row.net), strong: true),
          mini('Lucro', money(row.profit), strong: true, color: tone),
          mini('Margem', percent2(row.marginPct), color: tone),
          mini('Markup', percent2(row.markupPct)),
        ]),
        for (final msg in row.issues)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Row(children: [Icon(Icons.warning_amber_rounded, size: 15, color: c.warn), const SizedBox(width: 6), Expanded(child: Text(msg, style: TextStyle(color: c.warn, fontSize: 12, fontWeight: FontWeight.w600)))]),
          ),
      ]),
    );
  }
}
