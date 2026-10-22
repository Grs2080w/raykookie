import 'package:flutter/material.dart';
import 'prices_screen.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/pricing.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/category_chip.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

class _StockData {
  _StockData(this.ingredients, this.categories);
  final List<Ingredient> ingredients;
  final List<Category> categories;
}

class StockScreen extends StatelessWidget {
  const StockScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    return Loader<_StockData>(
      load: () async {
        final r = await Future.wait([
          api.get('/api/ingredients'),
          api.get('/api/categories?kind=ingredient'),
        ]);
        return _StockData(
          [for (final e in r[0] as List) Ingredient.fromJson(e)],
          [for (final e in r[1] as List) Category.fromJson(e)],
        );
      },
      builder: (context, d, reload) => _StockView(data: d, reload: reload),
    );
  }
}

class _StockView extends StatefulWidget {
  const _StockView({required this.data, required this.reload});
  final _StockData data;
  final Future<void> Function() reload;
  @override
  State<_StockView> createState() => _StockViewState();
}

class _StockViewState extends State<_StockView> {
  String _search = '';
  Object? _filter; // null = todos, 'low', ou id da categoria

  ApiClientRef get _api => context.read<Session>().ownerApi;

  Future<void> _edit([Ingredient? i]) async {
    final saved = await showFormSheet<bool>(
      context,
      title: i == null ? 'Novo ingrediente' : 'Editar ingrediente',
      child: IngredientForm(existing: i, categories: widget.data.categories),
    );
    if (saved == true) await widget.reload();
  }

  Future<void> _scanReceipt() async {
    final done = await context.push<bool>('/painel/estoque/nota');
    if (done == true) await widget.reload();
  }

  Future<void> _step(Ingredient i, int d) async {
    final next =
        ((i.stockQty + d).clamp(0, double.infinity) * 1000).round() / 1000;
    try {
      await _api.patch('/api/ingredients/${i.id}', {'stock_qty': next});
      await widget.reload();
    } on ApiException catch (e) {
      Toast.error(e.message);
    }
  }

  Future<void> _delete(Ingredient i) async {
    if (!await confirmDialog(
      context,
      title: 'Excluir “${i.name}”?',
      message: 'O ingrediente sai do estoque. Se ele estiver em alguma receita, o sistema avisa e não exclui.',
    ))
      return;
    try {
      await _api.delete('/api/ingredients/${i.id}');
      Toast.ok('Ingrediente excluído');
      await widget.reload();
    } on ApiException catch (e) {
      Toast.error(e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final all = widget.data.ingredients;
    final cats = {for (final x in widget.data.categories) x.id: x};
    final low = all
        .where((i) => stockStatus(i.stockQty, i.minStock) != StockStatus.ok)
        .length;
    final value = all.fold<double>(0, (s, i) => s + i.stockQty * i.unitCostValue);
    final rows = all.where((i) {
      if (_search.isNotEmpty &&
          !'${i.name} ${i.supplier}'.toLowerCase().contains(
            _search.toLowerCase(),
          ))
        return false;
      if (_filter == 'low')
        return stockStatus(i.stockQty, i.minStock) != StockStatus.ok;
      if (_filter is int) return i.categoryId == _filter;
      return true;
    }).toList();
    final usedCats = widget.data.categories
        .where((cat) => all.any((i) => i.categoryId == cat.id))
        .toList();

    Widget stat(String label, String value, {Color? color}) => Expanded(
      child: AppCard(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: TextStyle(color: c.muted, fontSize: 12.5)),
            const SizedBox(height: 2),
            FittedBox(
              fit: BoxFit.scaleDown,
              child: Text(
                value,
                style: display(context, size: 24, color: color),
              ),
            ),
          ],
        ),
      ),
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 12),
        const PageTitle(
          'Estoque de ingredientes',
          subtitle: 'O custo por grama, mililitro ou unidade é calculado sozinho a partir do preço da embalagem.',
        ),
        Row(
          children: [
            stat('Ingredientes', '${all.length}'),
            const SizedBox(width: 8),
            stat('Valor em estoque', money(value)),
            const SizedBox(width: 8),
            stat('Repor', '$low', color: low > 0 ? c.warn : null),
          ],
        ),
        const SizedBox(height: 14),
        TextField(
          decoration: const InputDecoration(
            hintText: 'Buscar ingrediente ou fornecedor',
            prefixIcon: Icon(Icons.search_rounded),
          ),
          onChanged: (v) => setState(() => _search = v),
        ),
        const SizedBox(height: 10),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: [
              ChoiceChip(
                label: const Text('Todos'),
                selected: _filter == null,
                onSelected: (_) => setState(() => _filter = null),
              ),
              const SizedBox(width: 8),
              ChoiceChip(
                label: Text('Repor${low > 0 ? ' ($low)' : ''}'),
                selected: _filter == 'low',
                onSelected: (_) => setState(() => _filter = 'low'),
              ),
              for (final cat in usedCats) ...[
                const SizedBox(width: 8),
                ChoiceChip(
                  label: Text(cat.name),
                  selected: _filter == cat.id,
                  onSelected: (_) => setState(
                    () => _filter = _filter == cat.id ? null : cat.id,
                  ),
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: 14),
        Row(
          children: [
            Expanded(
              flex: 5,
              child: BusyButton(
                label: 'Novo ingrediente',
                icon: Icons.add_rounded,
                onPressed: () => _edit(),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              flex: 4,
              child: BusyButton(
                label: 'Ler nota',
                icon: Icons.qr_code_scanner_rounded,
                outlined: true,
                onPressed: _scanReceipt,
              ),
            ),
          ],
        ),
        const SizedBox(height: 14),
        if (all.isEmpty)
          EmptyState(
            icon: Icons.inventory_2_outlined,
            title: 'Seu estoque está vazio',
            text: 'Cadastre os ingredientes com o preço da embalagem. Eles alimentam o cálculo das receitas.',
            action: BusyButton(
              label: 'Adicionar o primeiro',
              expand: false,
              icon: Icons.add_rounded,
              onPressed: () => _edit(),
            ),
          )
        else if (rows.isEmpty)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(28),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(24),
              border: Border.all(color: c.line),
            ),
            child: Text(
              'Nenhum ingrediente com esse filtro.',
              textAlign: TextAlign.center,
              style: TextStyle(color: c.muted),
            ),
          )
        else
          for (final i in rows) _row(context, i, cats[i.categoryId]),
      ],
    );
  }

  Widget _row(BuildContext context, Ingredient i, Category? cat) {
    final c = context.c;
    final st = stockStatus(i.stockQty, i.minStock);
    final (label, tone) = switch (st) {
      StockStatus.ok => ('Estoque ok', 'ok'),
      StockStatus.baixo => ('Estoque baixo', 'warn'),
      StockStatus.zerado => ('Acabou', 'bad'),
    };
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: AppCard(
        padding: const EdgeInsets.fromLTRB(14, 12, 6, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        i.name,
                        style: const TextStyle(
                          fontWeight: FontWeight.w800,
                          fontSize: 16,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Wrap(
                        spacing: 8,
                        runSpacing: 4,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          if (cat != null)
                            CategoryChip(
                              name: cat.name,
                              icon: cat.icon,
                              color: cat.color,
                            ),
                          if (i.supplier.isNotEmpty)
                            Text(
                              i.supplier,
                              style: TextStyle(color: c.muted, fontSize: 12),
                            ),
                        ],
                      ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.only(right: 8, top: 2),
                  child: AppBadge(label, tone: tone),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(4),
                child: LinearProgressIndicator(
                  minHeight: 8,
                  // cheio = o dobro do estoque mínimo (sem mínimo definido: cheio se houver algo)
                  value: i.minStock > 0
                      ? (i.stockQty / (i.minStock * 2)).clamp(0.0, 1.0)
                      : (i.stockQty > 0 ? 1.0 : 0.0),
                  backgroundColor: c.surface2,
                  color: switch (st) {
                    StockStatus.ok => c.ok,
                    StockStatus.baixo => c.warn,
                    StockStatus.zerado => c.bad,
                  },
                ),
              ),
            ),
            const SizedBox(height: 8),
            Text.rich(
              TextSpan(
                style: TextStyle(color: c.muted, fontSize: 13.5),
                children: [
                  TextSpan(
                    text:
                        '${number(i.packageSize)} ${i.unit} · ${money(i.packageCost)}',
                  ),
                  const TextSpan(text: '  ·  '),
                  TextSpan(
                    text: '${moneyPrecise(i.unitCostValue)}/${i.unit}',
                    style: TextStyle(color: c.ink, fontWeight: FontWeight.w700),
                  ),
                ],
              ),
            ),
            Row(
              children: [
                IconButton(
                  onPressed: () => _step(i, -1),
                  icon: const Icon(Icons.remove_rounded, size: 18),
                  tooltip: 'Diminuir 1',
                  style: IconButton.styleFrom(
                    side: BorderSide(color: c.line),
                    minimumSize: const Size(40, 40),
                  ),
                ),
                SizedBox(
                  width: 46,
                  child: Text(
                    number(i.stockQty),
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      fontWeight: FontWeight.w800,
                      fontSize: 16,
                    ),
                  ),
                ),
                IconButton(
                  onPressed: () => _step(i, 1),
                  icon: const Icon(Icons.add_rounded, size: 18),
                  tooltip: 'Aumentar 1',
                  style: IconButton.styleFrom(
                    side: BorderSide(color: c.line),
                    minimumSize: const Size(40, 40),
                  ),
                ),
                const Spacer(),
                IconButton(
                  onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => PricesScreen(ingredient: i))).then((_) => widget.reload()),
                  icon: const Icon(Icons.sell_outlined),
                  tooltip: 'Preços de ${i.name}',
                ),
                IconButton(
                  onPressed: () => _edit(i),
                  icon: const Icon(Icons.edit_outlined),
                  tooltip: 'Editar ${i.name}',
                ),
                IconButton(
                  onPressed: () => _delete(i),
                  icon: Icon(Icons.delete_outline_rounded, color: c.bad),
                  tooltip: 'Excluir ${i.name}',
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

typedef ApiClientRef = ApiClient;

/// Formulário de ingrediente (novo ou edição), em folha inferior.
class IngredientForm extends StatefulWidget {
  const IngredientForm({super.key, this.existing, required this.categories});
  final Ingredient? existing;
  final List<Category> categories;
  @override
  State<IngredientForm> createState() => _IngredientFormState();
}

class _IngredientFormState extends State<IngredientForm> {
  late final _name = TextEditingController(text: widget.existing?.name ?? '');
  late final _size = TextEditingController(
    text: widget.existing == null ? '' : fieldNum(widget.existing!.packageSize),
  );
  late final _cost = TextEditingController(
    text: widget.existing == null ? '' : fieldNum(widget.existing!.packageCost),
  );
  late final _stock = TextEditingController(
    text: widget.existing == null ? '1' : fieldNum(widget.existing!.stockQty),
  );
  late final _min = TextEditingController(
    text: widget.existing == null ? '' : fieldNum(widget.existing!.minStock),
  );
  late final _supplier = TextEditingController(
    text: widget.existing?.supplier ?? '',
  );
  late final _notes = TextEditingController(text: widget.existing?.notes ?? '');
  late int? _category = widget.existing?.categoryId;
  late String _unit = widget.existing?.unit ?? 'g';
  bool _busy = false;

  @override
  void dispose() {
    for (final c in [_name, _size, _cost, _stock, _min, _supplier, _notes]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    final body = {
      'name': _name.text,
      'category_id': _category?.toString() ?? '',
      'unit': _unit,
      'stock_qty': _stock.text,
      'package_size': _size.text,
      'package_cost': _cost.text,
      'min_stock': _min.text,
      'supplier': _supplier.text,
      'notes': _notes.text,
    };
    if (_name.text.trim().isEmpty)
      return Toast.error('Informe o nome do ingrediente.');
    if (parseNum(_size.text) <= 0)
      return Toast.error('O tamanho da embalagem precisa ser maior que zero.');
    final api = context.read<Session>().ownerApi;
    setState(() => _busy = true);
    try {
      if (widget.existing == null) {
        await api.post('/api/ingredients', body);
      } else {
        await api.patch('/api/ingredients/${widget.existing!.id}', body);
      }
      Toast.ok(
        widget.existing == null
            ? 'Ingrediente adicionado'
            : 'Ingrediente atualizado',
      );
      if (mounted) Navigator.pop(context, true);
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final perUnit = unitCost(parseNum(_cost.text), parseNum(_size.text));
    return Column(
      children: [
        AppTextField(
          label: 'Nome',
          controller: _name,
          maxLength: 80,
          hint: 'Ex.: Leite condensado',
          capitalization: TextCapitalization.sentences,
        ),
        const SizedBox(height: 14),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: SelectField<int?>(
                label: 'Categoria',
                value: _category,
                items: [
                  const DropdownMenuItem(
                    value: null,
                    child: Text('Sem categoria'),
                  ),
                  for (final x in widget.categories)
                    DropdownMenuItem(
                      value: x.id,
                      child: Text(x.name, overflow: TextOverflow.ellipsis),
                    ),
                ],
                onChanged: (v) => setState(() => _category = v),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: SelectField<String>(
                label: 'Unidade',
                value: _unit,
                items: const [
                  DropdownMenuItem(value: 'g', child: Text('Gramas (g)')),
                  DropdownMenuItem(value: 'ml', child: Text('Mililitros (ml)')),
                  DropdownMenuItem(value: 'un', child: Text('Unidades (un)')),
                ],
                onChanged: (v) => setState(() => _unit = v ?? 'g'),
              ),
            ),
          ],
        ),
        const SizedBox(height: 14),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: NumberField(
                label: 'Embalagem ($_unit)',
                controller: _size,
                hint: 'Ex.: 395',
                onChanged: (_) => setState(() {}),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: NumberField(
                label: 'Preço (R\$)',
                controller: _cost,
                hint: 'Ex.: 7,50',
                onChanged: (_) => setState(() {}),
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          decoration: BoxDecoration(
            color: c.pinkTint,
            borderRadius: BorderRadius.circular(16),
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  'Custo por $_unit',
                  style: TextStyle(
                    color: c.pinkStrong,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              Text(
                moneyPrecise(perUnit),
                style: display(context, size: 22, color: c.pinkStrong),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: NumberField(
                label: 'Embalagens em estoque',
                helper: 'Quantas embalagens fechadas você tem.',
                controller: _stock,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: NumberField(
                label: 'Estoque mínimo',
                helper: 'Avisa quando chegar nesse número.',
                controller: _min,
              ),
            ),
          ],
        ),
        const SizedBox(height: 14),
        AppTextField(label: 'Fornecedor', controller: _supplier, maxLength: 80),
        const SizedBox(height: 14),
        AppTextField(
          label: 'Observações',
          controller: _notes,
          maxLength: 300,
          maxLines: 3,
        ),
        const SizedBox(height: 20),
        Row(
          children: [
            Expanded(
              child: BusyButton(
                label: 'Cancelar',
                outlined: true,
                onPressed: () => Navigator.pop(context),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: BusyButton(label: 'Salvar', busy: _busy, onPressed: _save),
            ),
          ],
        ),
      ],
    );
  }
}
