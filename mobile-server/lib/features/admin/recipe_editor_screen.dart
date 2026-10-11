import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/pricing.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

class _EditorData {
  _EditorData(
    this.ingredients,
    this.categories,
    this.settings,
    this.fixedTotal,
    this.recipe,
  );
  final List<Ingredient> ingredients;
  final List<Category> categories;
  final Settings settings;
  final double fixedTotal;
  final RecipeRow? recipe;
}

/// Receita nova (id == null) ou existente.
class RecipeEditorScreen extends StatelessWidget {
  const RecipeEditorScreen({super.key, this.id});
  final int? id;

  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    return Scaffold(
      appBar: AppBar(title: Text(id == null ? 'Nova receita' : 'Receita')),
      body: Loader<_EditorData>(
        scroll: false,
        load: () async {
          final r = await Future.wait([
            api.get('/api/ingredients'),
            api.get('/api/categories?kind=recipe'),
            api.get('/api/settings'),
            api.get('/api/fixed-costs'),
            if (id != null) api.get('/api/recipes/$id'),
          ]);
          final costs = [for (final e in r[3] as List) FixedCost.fromJson(e)];
          return _EditorData(
            [for (final e in r[0] as List) Ingredient.fromJson(e)],
            [for (final e in r[1] as List) Category.fromJson(e)],
            Settings.fromJson(r[2] as Map<String, dynamic>),
            costs.fold<double>(0, (s, c) => s + c.amount),
            id == null
                ? null
                : RecipeRow.fromJson(r[4] as Map<String, dynamic>),
          );
        },
        builder: (context, d, reload) => _Editor(data: d),
      ),
    );
  }
}

class _Row {
  _Row({this.ingredientId, String qty = ''})
    : qty = TextEditingController(text: qty);
  int? ingredientId;
  final TextEditingController qty;
}

/// Uma aba de receita do produto (Receita 1, Receita 2…): nome, rendimento, preparo e ingredientes.
class _Variant {
  _Variant({this.id, required String name, String yieldText = '', String hoursText = '', List<_Row>? rows, this.isDefault = false})
    : name = TextEditingController(text: name),
      yieldCtl = TextEditingController(text: yieldText),
      hours = TextEditingController(text: hoursText),
      rows = rows ?? [_Row()];
  int? id; // nulo = ainda não gravada
  final TextEditingController name, yieldCtl, hours;
  final List<_Row> rows;
  bool isDefault;

  /// Cópia (aba "+", copiando a atual).
  _Variant copy(String name) => _Variant(
    name: name,
    yieldText: yieldCtl.text,
    hoursText: hours.text,
    rows: [for (final r in rows) _Row(ingredientId: r.ingredientId, qty: r.qty.text)],
  );

  Map<String, dynamic> get body => {
    'name': name.text.trim().isEmpty ? 'Receita' : name.text.trim(),
    'yield_units': parseNum(yieldCtl.text) > 0 ? parseNum(yieldCtl.text) : 1,
    'prep_hours': parseNum(hours.text),
    'items': [
      for (final r in rows.where((r) => r.ingredientId != null))
        {'ingredient_id': r.ingredientId.toString(), 'qty': parseNum(r.qty.text)},
    ],
  };

  void dispose() {
    for (final c in [name, yieldCtl, hours]) {
      c.dispose();
    }
    for (final r in rows) {
      r.qty.dispose();
    }
  }
}

class _Editor extends StatefulWidget {
  const _Editor({required this.data});
  final _EditorData data;
  @override
  State<_Editor> createState() => _EditorState();
}

class _EditorState extends State<_Editor> {
  late final RecipeRow? _r = widget.data.recipe;
  late final _name = TextEditingController(text: _r?.name ?? '');
  late final _salePrice = TextEditingController(
    text: _r?.salePrice == null ? '' : fieldNum(_r!.salePrice!),
  );
  late final _unitWeight = TextEditingController(
    text: _r == null || _r.unitWeightG == 0 ? '' : fieldNum(_r.unitWeightG),
  );
  late final _margin = TextEditingController(
    text: _r == null ? '100' : fieldNum(_r.marginPct),
  );
  late final _notes = TextEditingController(text: _r?.notes ?? '');
  late int? _category = _r?.categoryId;
  /// Abas de receita do produto; a padrão é a que vale para o cardápio, os combos e o lucro.
  late final List<_Variant> _variants = _initVariants();
  /// A padrão como veio do servidor: o formulário do produto grava nela.
  late final _Variant _orig = _variants.firstWhere((v) => v.isDefault, orElse: () => _variants.first);
  late int _tab = _variants.indexOf(_orig);
  final List<int> _deleted = [];
  _Variant get _cur => _variants[_tab];
  _Variant get _def => _variants.firstWhere((v) => v.isDefault, orElse: () => _variants.first);
  TextEditingController get _yield => _cur.yieldCtl;
  TextEditingController get _hours => _cur.hours;
  List<_Row> get _rows => _cur.rows;

  List<_Variant> _initVariants() {
    final r = _r;
    List<_Row> rowsOf(List<RecipeItem> items) =>
        items.isEmpty ? [_Row()] : [for (final it in items) _Row(ingredientId: it.ingredientId, qty: fieldNum(it.qty))];
    if (r == null) return [_Variant(name: 'Receita 1', isDefault: true)];
    if (r.variants.isEmpty) {
      return [
        _Variant(
          name: 'Receita 1',
          yieldText: fieldNum(r.yieldUnits),
          hoursText: r.prepHours == 0 ? '' : fieldNum(r.prepHours),
          rows: rowsOf(r.items),
          isDefault: true,
        ),
      ];
    }
    return [
      for (final v in r.variants)
        _Variant(
          id: v.id,
          name: v.name,
          yieldText: fieldNum(v.yieldUnits),
          hoursText: v.prepHours == 0 ? '' : fieldNum(v.prepHours),
          rows: rowsOf(v.items),
          isDefault: v.isDefault,
        ),
    ];
  }
  bool _busy = false, _photoBusy = false;
  String? _photoV;
  Uint8List? _pending; // foto de receita nova: enviada depois de salvar
  Uint8List? _preview;

  @override
  void initState() {
    super.initState();
    _photoV = _r?.photoV;
  }

  @override
  void dispose() {
    for (final c in [_name, _salePrice, _unitWeight, _margin, _notes]) {
      c.dispose();
    }
    for (final v in _variants) {
      v.dispose();
    }
    super.dispose();
  }

  Ingredient? _ing(int? id) =>
      widget.data.ingredients.where((i) => i.id == id).firstOrNull;

  /// Estimativa da aba aberta.
  PricingResult get _calc => _calcOf(_cur);

  /// Estimativa da receita padrão (o preço do cardápio vem dela).
  PricingResult get _defCalc => _calcOf(_def);

  PricingResult _calcOf(_Variant v) => calcRecipe(
    items: [
      for (final r in v.rows)
        PricingItem(
          parseNum(r.qty.text),
          _ing(r.ingredientId)?.unitCostValue ?? 0,
        ),
    ],
    yieldUnits: parseNum(v.yieldCtl.text) > 0 ? parseNum(v.yieldCtl.text) : 1,
    marginPct: parseNum(_margin.text),
    prepHours: parseNum(v.hours.text),
    hourlyRate: widget.data.settings.hourlyRate,
    fixedMonthly: widget.data.fixedTotal,
    monthlyUnits: widget.data.settings.monthlyUnits,
  );

  /// Preço do cardápio fixado vale no lugar do calculado.
  PricingResult get _pricing => withSalePrice(_calc, parseNum(_salePrice.text));

  Map<String, dynamic> get _body => {
    'name': _name.text,
    'category_id': _category?.toString() ?? '',
    'yield_units': _orig.body['yield_units'],
    'margin_pct': parseNum(_margin.text),
    'prep_hours': _orig.body['prep_hours'],
    'unit_weight_g': parseNum(_unitWeight.text),
    'sale_price': parseNum(_salePrice.text) > 0 ? parseNum(_salePrice.text) : null,
    'notes': _notes.text,
    'items': _orig.body['items'],
  };

  String _mime(Uint8List b) => b.length > 3 && b[0] == 0x89 && b[1] == 0x50
      ? 'image/png'
      : (b.length > 11 && b[8] == 0x57 && b[9] == 0x45
            ? 'image/webp'
            : 'image/jpeg');

  Future<void> _pickPhoto(ImageSource source) async {
    final api = context.read<Session>().ownerApi;
    try {
      final x = await ImagePicker().pickImage(
        source: source,
        maxWidth: 1200,
        maxHeight: 1200,
        imageQuality: 82,
      );
      if (x == null) return;
      final bytes = await x.readAsBytes();
      if (bytes.length > 2000000)
        return Toast.warn(
          'Essa foto é pesada demais (máximo de 2 MB). Escolha outra ou tire uma nova.',
        );
      setState(() => _photoBusy = true);
      if (_r != null) {
        await api.putBytes('/api/recipes/${_r.id}/photo', bytes, _mime(bytes));
        _photoV = DateTime.now().millisecondsSinceEpoch.toString();
        _pending = null;
        Toast.ok('Foto salva');
      } else {
        _pending = bytes;
      }
      setState(() => _preview = bytes);
    } on ApiException catch (e) {
      Toast.error(e.message);
    } catch (_) {
      Toast.error('Não foi possível abrir a foto. Tente de novo.');
    } finally {
      if (mounted) setState(() => _photoBusy = false);
    }
  }

  Future<void> _removePhoto() async {
    setState(() => _photoBusy = true);
    try {
      if (_r != null && _photoV != null) {
        await context.read<Session>().ownerApi.delete(
          '/api/recipes/${_r.id}/photo',
        );
        Toast.ok('Foto removida');
      }
      setState(() {
        _pending = null;
        _preview = null;
        _photoV = null;
      });
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _photoBusy = false);
    }
  }

  Future<void> _save() async {
    if (_name.text.trim().isEmpty)
      return Toast.error('Dê um nome para a receita.');
    final api = context.read<Session>().ownerApi;
    setState(() => _busy = true);
    try {
      if (_r != null) {
        await api.put('/api/recipes/${_r.id}', _body);
        await _saveVariants(api, _r.id);
        Toast.ok('Receita salva');
      } else {
        final created =
            await api.post('/api/recipes', _body) as Map<String, dynamic>;
        await _saveVariants(api, (created['id'] as num).toInt());
        if (_pending != null) {
          try {
            await api.putBytes(
              '/api/recipes/${created['id']}/photo',
              _pending!,
              _mime(_pending!),
            );
          } on ApiException catch (e) {
            Toast.warn(
              'Receita criada, mas a foto não foi enviada: ${e.message}',
            );
          }
        }
        Toast.ok('Receita criada');
      }
      if (mounted) context.canPop() ? context.pop() : context.go('/painel/receitas');
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Grava as abas: a padrão original já foi pelo formulário do produto (aqui só o nome dela); as outras são
  /// criadas ou salvas; depois a troca de padrão e as exclusões.
  Future<void> _saveVariants(ApiClient api, int recipeId) async {
    final base = '/api/recipes/$recipeId/variants';
    if (_orig.id == null) {
      final rec = await api.get('/api/recipes/$recipeId') as Map<String, dynamic>;
      final list = rec['variants'] as List? ?? const [];
      final standard = list.cast<Map>().where((v) => v['is_default'] == true).firstOrNull;
      _orig.id = standard == null ? null : (standard['id'] as num).toInt();
    }
    for (final v in _variants) {
      if (v.id == null) {
        final list = await api.post(base, {'name': v.name.text.trim()}) as List;
        v.id = ((list.last as Map)['id'] as num).toInt();
      }
      await api.put('$base/${v.id}', v.body);
    }
    if (!identical(_def, _orig) && _def.id != null) await api.post('$base/${_def.id}/default', const {});
    for (final id in _deleted) {
      await api.delete('$base/$id');
    }
  }

  /// Aba "+": receita nova, vazia ou copiando a aba aberta.
  Future<void> _addVariant() async {
    if (_variants.length >= 10) return Toast.warn('Cada produto pode ter até 10 receitas.');
    final name = 'Receita ${_variants.length + 1}';
    final copy = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Nova receita'),
        content: Text('Começar do zero ou copiar os ingredientes de “${_cur.name.text}”?'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Começar vazia')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Copiar esta')),
        ],
      ),
    );
    if (copy == null) return;
    setState(() {
      _variants.add(copy ? _cur.copy(name) : _Variant(name: name));
      _tab = _variants.length - 1;
    });
  }

  /// Marca a aba aberta como padrão (vale ao salvar).
  void _makeDefault() => setState(() {
    for (final v in _variants) {
      v.isDefault = identical(v, _cur);
    }
  });

  /// Exclui a aba aberta (a padrão não pode; vale ao salvar).
  Future<void> _deleteVariant() async {
    if (_cur.isDefault) return;
    if (!await confirmDialog(
      context,
      title: 'Excluir “${_cur.name.text}”?',
      message: 'Os ingredientes desta receita serão apagados quando você salvar. As outras receitas continuam.',
    )) {
      return;
    }
    setState(() {
      final gone = _variants.removeAt(_tab);
      if (gone.id != null) _deleted.add(gone.id!);
      gone.dispose();
      _tab = _variants.indexOf(_def);
    });
  }

  Future<void> _delete() async {
    final api = context.read<Session>().ownerApi;
    if (!await confirmDialog(
      context,
      title: 'Excluir “${_r!.name}”?',
      message: 'A receita e a lista de ingredientes dela serão apagadas. Pedidos antigos continuam salvos.',
    ))
      return;
    try {
      await api.delete('/api/recipes/${_r.id}');
      Toast.ok('Receita excluída');
      if (mounted) context.canPop() ? context.pop() : context.go('/painel/receitas');
    } on ApiException catch (e) {
      Toast.error(e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final api = context.read<Session>().ownerApi;
    final p = _pricing;
    final d = widget.data;
    final used = _rows.map((r) => r.ingredientId).whereType<int>().toSet();
    final lines = [
      for (final r in _rows)
        (
          r,
          _ing(r.ingredientId),
          parseNum(r.qty.text) * (_ing(r.ingredientId)?.unitCostValue ?? 0),
        ),
    ];
    final priciest = lines.length > 1
        ? (lines.toList()..sort((a, b) => b.$3.compareTo(a.$3))).first
        : null;
    final photoUrl = _r != null && _photoV != null
        ? api.absolute('/api/fotos/${_r.id}?v=$_photoV')
        : null;
    final yieldN = parseNum(_yield.text) > 0 ? parseNum(_yield.text) : 1;
    double share(double v) => p.rounded * yieldN > 0
        ? (v / (p.rounded * yieldN) * 100).clamp(0, 1000).toDouble()
        : 0;

    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
            children: [
              TextField(
                controller: _name,
                maxLength: 80,
                onChanged: (_) => setState(() {}),
                textCapitalization: TextCapitalization.sentences,
                style: display(context, size: 30),
                decoration: InputDecoration(
                  hintText: 'Nome da receita',
                  counterText: '',
                  border: InputBorder.none,
                  enabledBorder: InputBorder.none,
                  focusedBorder: InputBorder.none,
                  filled: false,
                  hintStyle: display(
                    context,
                    size: 30,
                    color: c.muted.withValues(alpha: 0.5),
                  ),
                ),
              ),
              Container(
                height: 3,
                width: 60,
                decoration: BoxDecoration(
                  color: c.pink,
                  borderRadius: BorderRadius.circular(3),
                ),
              ),
              const SizedBox(height: 16),
              AppCard(
                child: Column(
                  children: [
                    SelectField<int?>(
                      label: 'Categoria',
                      value: _category,
                      items: [
                        const DropdownMenuItem(
                          value: null,
                          child: Text('Sem categoria'),
                        ),
                        for (final x in d.categories)
                          DropdownMenuItem(
                            value: x.id,
                            child: Text(
                              x.name,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                      ],
                      onChanged: (v) => setState(() => _category = v),
                    ),
                    const SizedBox(height: 14),
                    NumberField(
                      label: 'Margem de lucro (%)',
                      controller: _margin,
                      helper: 'Vale para todas as receitas deste produto.',
                      onChanged: (_) => setState(() {}),
                    ),
                    const SizedBox(height: 14),
                    NumberField(
                      label: 'Preço do cardápio (R\$, opcional)',
                      controller: _salePrice,
                      hint: _defCalc.rounded > 0 ? fieldNum(_defCalc.rounded) : '',
                      helper: parseNum(_salePrice.text) > 0
                          ? 'Fixado por você. A receita padrão (${_def.name.text}) sugere ${money(_defCalc.rounded)}; a margem real é ${withSalePrice(_defCalc, parseNum(_salePrice.text)).realMarginPct.toStringAsFixed(1).replaceAll('.', ',')}%.'
                          : 'Em branco usa o preço calculado da receita padrão, ${_def.name.text} (${money(_defCalc.rounded)}). Fixe um valor para ser avisada se a margem cair quando um ingrediente subir.',
                      onChanged: (_) => setState(() {}),
                    ),
                    const SizedBox(height: 14),
                    NumberField(
                      label: 'Gramatura por unidade (g)',
                      controller: _unitWeight,
                      hint: 'Ex.: 60',
                      helper: parseNum(_unitWeight.text) > 0
                          ? 'Receita inteira: ${number(parseNum(_unitWeight.text) * yieldN)} g (${number(yieldN)} un × ${number(parseNum(_unitWeight.text))} g)'
                          : 'Quanto pesa cada unidade pronta',
                      onChanged: (_) => setState(() {}),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 12),
              // foto da Landing page
              AppCard(
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _preview != null
                        ? ClipRRect(
                            borderRadius: BorderRadius.circular(18),
                            child: Image.memory(
                              _preview!,
                              width: 88,
                              height: 88,
                              fit: BoxFit.cover,
                            ),
                          )
                        : Container(
                            width: 88,
                            height: 88,
                            clipBehavior: Clip.antiAlias,
                            decoration: BoxDecoration(
                              color: c.surface2,
                              borderRadius: BorderRadius.circular(18),
                            ),
                            child: photoUrl != null
                                ? Image.network(
                                    photoUrl,
                                    headers: {
                                      if (api.token != null)
                                        'authorization': 'Bearer ${api.token}',
                                    },
                                    fit: BoxFit.cover,
                                    errorBuilder: (_, _, _) => Icon(
                                      Icons.add_photo_alternate_outlined,
                                      color: c.muted,
                                      size: 28,
                                    ),
                                  )
                                : Icon(
                                    Icons.add_photo_alternate_outlined,
                                    color: c.muted,
                                    size: 28,
                                  ),
                          ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text.rich(
                            TextSpan(
                              children: [
                                const TextSpan(
                                  text: 'Foto para a Landing page ',
                                  style: TextStyle(fontWeight: FontWeight.w800),
                                ),
                                TextSpan(
                                  text: '(opcional)',
                                  style: TextStyle(color: c.muted),
                                ),
                              ],
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            'Na Landing page aparecem apenas a foto, o nome e o preço por unidade. Ingredientes, custos e margem nunca são exibidos.',
                            style: TextStyle(
                              color: c.muted,
                              fontSize: 13,
                              height: 1.35,
                            ),
                          ),
                          const SizedBox(height: 6),
                          p.rounded > 0
                              ? Text.rich(
                                  TextSpan(
                                    style: const TextStyle(
                                      fontWeight: FontWeight.w600,
                                      fontSize: 13.5,
                                    ),
                                    children: [
                                      const TextSpan(text: 'Preço exibido: '),
                                      TextSpan(
                                        text: money(p.rounded),
                                        style: TextStyle(color: c.pinkStrong),
                                      ),
                                    ],
                                  ),
                                )
                              : Text(
                                  'Sem ingredientes a receita fica sem preço e não aparece na Landing page.',
                                  style: TextStyle(
                                    color: c.warn,
                                    fontSize: 13.5,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                          const SizedBox(height: 10),
                          Wrap(
                            spacing: 8,
                            runSpacing: 6,
                            children: [
                              if (_photoBusy)
                                const SizedBox(
                                  width: 24,
                                  height: 24,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2.4,
                                  ),
                                )
                              else ...[
                                FilledButton.tonal(
                                  onPressed: () => showModalBottomSheet<void>(
                                    context: context,
                                    builder: (ctx) => SafeArea(
                                      child: Column(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          ListTile(
                                            leading: const Icon(
                                              Icons.photo_library_outlined,
                                            ),
                                            title: const Text(
                                              'Escolher da galeria',
                                            ),
                                            onTap: () {
                                              Navigator.pop(ctx);
                                              _pickPhoto(ImageSource.gallery);
                                            },
                                          ),
                                          ListTile(
                                            leading: const Icon(
                                              Icons.photo_camera_outlined,
                                            ),
                                            title: const Text('Tirar uma foto'),
                                            onTap: () {
                                              Navigator.pop(ctx);
                                              _pickPhoto(ImageSource.camera);
                                            },
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                                  style: FilledButton.styleFrom(
                                    backgroundColor: c.pinkTint,
                                    foregroundColor: c.pinkStrong,
                                    minimumSize: const Size(0, 40),
                                  ),
                                  child: Text(
                                    photoUrl != null || _preview != null
                                        ? 'Trocar foto'
                                        : 'Adicionar foto',
                                  ),
                                ),
                                if (photoUrl != null || _preview != null)
                                  TextButton(
                                    onPressed: _removePhoto,
                                    style: TextButton.styleFrom(
                                      foregroundColor: c.bad,
                                    ),
                                    child: const Text('Remover'),
                                  ),
                              ],
                            ],
                          ),
                          if (_r == null && _pending != null)
                            Padding(
                              padding: const EdgeInsets.only(top: 6),
                              child: Text(
                                'A foto é enviada quando você salvar a receita.',
                                style: TextStyle(color: c.muted, fontSize: 12),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              const SectionTitle('Receitas deste produto'),
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  children: [
                    for (final (i, v) in _variants.indexed)
                      Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: ChoiceChip(
                          key: Key('variant_tab_$i'),
                          label: Text('${v.name.text.trim().isEmpty ? 'Receita ${i + 1}' : v.name.text.trim()}${v.isDefault ? ' ★' : ''}'),
                          selected: i == _tab,
                          onSelected: (_) => setState(() => _tab = i),
                        ),
                      ),
                    IconButton.filledTonal(
                      key: const Key('variant_add'),
                      tooltip: 'Nova receita para este produto',
                      onPressed: _addVariant,
                      icon: const Icon(Icons.add),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 10),
              AppCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    AppTextField(
                      key: ValueKey('variant_name_$_tab'),
                      label: 'Nome desta receita',
                      controller: _cur.name,
                      maxLength: 40,
                      onChanged: (_) => setState(() {}),
                    ),
                    const SizedBox(height: 14),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: NumberField(
                            key: ValueKey('variant_yield_$_tab'),
                            label: 'Rende (unidades)',
                            controller: _yield,
                            hint: 'Ex.: 20',
                            onChanged: (_) => setState(() {}),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: NumberField(
                            key: ValueKey('variant_hours_$_tab'),
                            label: 'Tempo de produção (h)',
                            controller: _hours,
                            hint: 'Ex.: 1,5',
                            onChanged: (_) => setState(() {}),
                          ),
                        ),
                      ],
                    ),
                    Text(
                      'Valor da hora: ${money(d.settings.hourlyRate)} (em Custos e ajustes)',
                      style: TextStyle(color: c.muted, fontSize: 12.5),
                    ),
                    const SizedBox(height: 8),
                    if (_cur.isDefault)
                      Text(
                        '★ Receita padrão: é dela o preço do cardápio, dos combos, do lucro e das metas.',
                        style: TextStyle(color: c.muted, fontSize: 13),
                      )
                    else
                      Wrap(
                        spacing: 8,
                        children: [
                          TextButton.icon(
                            key: const Key('variant_make_default'),
                            onPressed: _makeDefault,
                            icon: const Icon(Icons.star_outline_rounded),
                            label: const Text('Definir como padrão'),
                          ),
                          TextButton.icon(
                            onPressed: _deleteVariant,
                            icon: const Icon(Icons.delete_outline_rounded),
                            label: const Text('Excluir esta receita'),
                          ),
                        ],
                      ),
                  ],
                ),
              ),
              const SectionTitle('Ingredientes'),
              if (d.ingredients.isEmpty) ...[
                const InfoBanner(
                  'Seu estoque está vazio. Cadastre ingredientes para usá-los aqui.',
                  icon: Icons.warning_amber_rounded,
                ),
                const SizedBox(height: 10),
              ],
              for (final (i, row) in _rows.indexed)
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: AppCard(
                    padding: const EdgeInsets.fromLTRB(14, 12, 6, 12),
                    child: Column(
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: DropdownButtonFormField<int?>(
                                initialValue: row.ingredientId,
                                isExpanded: true,
                                decoration: const InputDecoration(
                                  hintText: 'Escolha um ingrediente…',
                                ),
                                borderRadius: BorderRadius.circular(16),
                                dropdownColor: c.surface,
                                items: [
                                  const DropdownMenuItem<int?>(
                                    value: null,
                                    child: Text('Escolha um ingrediente…'),
                                  ),
                                  for (final ing in d.ingredients.where(
                                    (x) =>
                                        x.id == row.ingredientId ||
                                        !used.contains(x.id),
                                  ))
                                    DropdownMenuItem<int?>(
                                      value: ing.id,
                                      child: Text(
                                        ing.name,
                                        overflow: TextOverflow.ellipsis,
                                      ),
                                    ),
                                ],
                                onChanged: (v) =>
                                    setState(() => row.ingredientId = v),
                              ),
                            ),
                            IconButton(
                              onPressed: () => setState(() {
                                if (_rows.length > 1) {
                                  _rows.removeAt(i).qty.dispose();
                                } else {
                                  row.ingredientId = null;
                                  row.qty.clear();
                                }
                              }),
                              icon: Icon(
                                Icons.delete_outline_rounded,
                                color: c.muted,
                              ),
                              tooltip: 'Remover ingrediente',
                            ),
                          ],
                        ),
                        const SizedBox(height: 10),
                        Padding(
                          padding: const EdgeInsets.only(right: 8),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Expanded(
                                child: NumberField(
                                  label: 'Quantidade usada',
                                  controller: row.qty,
                                  hint: '0',
                                  suffixText: lines[i].$2?.unit,
                                  onChanged: (_) => setState(() {}),
                                ),
                              ),
                              const SizedBox(width: 14),
                              Column(
                                crossAxisAlignment: CrossAxisAlignment.end,
                                children: [
                                  const SizedBox(height: 4),
                                  Text(
                                    lines[i].$2 == null
                                        ? '—'
                                        : '${moneyPrecise(lines[i].$2!.unitCostValue)} /${lines[i].$2!.unit}',
                                    style: TextStyle(
                                      color: c.muted,
                                      fontSize: 12.5,
                                    ),
                                  ),
                                  const SizedBox(height: 6),
                                  Text(
                                    money(lines[i].$3),
                                    style: const TextStyle(
                                      fontWeight: FontWeight.w800,
                                      fontSize: 17,
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              BusyButton(
                label: 'Adicionar ingrediente',
                icon: Icons.add_rounded,
                outlined: true,
                onPressed: () => setState(() => _rows.add(_Row())),
              ),
              if (priciest != null && priciest.$3 > 0 && priciest.$2 != null)
                Padding(
                  padding: const EdgeInsets.only(top: 10),
                  child: Text.rich(
                    TextSpan(
                      style: TextStyle(color: c.muted, fontSize: 13.5),
                      children: [
                        const TextSpan(
                          text: 'O ingrediente mais caro desta receita é ',
                        ),
                        TextSpan(
                          text: priciest.$2!.name,
                          style: TextStyle(
                            color: c.ink,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                        TextSpan(
                          text:
                              ', ${number((priciest.$3 / (p.ingredients > 0.0001 ? p.ingredients : 0.0001) * 100).round())}% do custo de ingredientes.',
                        ),
                      ],
                    ),
                  ),
                ),
              const SizedBox(height: 16),
              AppTextField(
                label: 'Modo de preparo e observações',
                controller: _notes,
                maxLength: 500,
                maxLines: 5,
              ),
              SectionTitle('Cálculo de preço · ${_cur.name.text.trim().isEmpty ? 'Receita ${_tab + 1}' : _cur.name.text.trim()}'),
              AppCard(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Preço sugerido por unidade',
                      style: TextStyle(color: c.muted, fontSize: 13),
                    ),
                    Text(
                      money(p.rounded),
                      style: display(
                        context,
                        size: 52,
                        color: c.pinkStrong,
                        height: 1,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      'Calculado: ${money(p.suggested)}, arredondado para cima em R\$ 0,50.',
                      style: TextStyle(color: c.muted, fontSize: 13),
                    ),
                    const Divider(height: 24),
                    for (final (label, v) in [
                      ('Ingredientes', p.ingredients),
                      (
                        'Mão de obra (${number(parseNum(_hours.text))} h)',
                        p.labor,
                      ),
                      ('Rateio de gastos', p.overhead),
                    ])
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 4),
                        child: Row(
                          children: [
                            Expanded(
                              child: Text(
                                label,
                                style: TextStyle(color: c.muted),
                              ),
                            ),
                            Text(money(v)),
                          ],
                        ),
                      ),
                    const Divider(height: 20),
                    Row(
                      children: [
                        const Expanded(
                          child: Text(
                            'Custo total da receita',
                            style: TextStyle(fontWeight: FontWeight.w700),
                          ),
                        ),
                        Text(
                          money(p.total),
                          style: const TextStyle(fontWeight: FontWeight.w800),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        const Expanded(
                          child: Text(
                            'Custo por unidade',
                            style: TextStyle(fontWeight: FontWeight.w700),
                          ),
                        ),
                        Text(
                          money(p.perUnit),
                          style: const TextStyle(fontWeight: FontWeight.w800),
                        ),
                      ],
                    ),
                    const SizedBox(height: 14),
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: c.ok.withValues(alpha: 0.12),
                        borderRadius: BorderRadius.circular(18),
                      ),
                      child: Column(
                        children: [
                          Row(
                            children: [
                              Expanded(
                                child: Text(
                                  'Lucro por unidade',
                                  style: TextStyle(
                                    color: c.ok,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                              ),
                              Text(
                                money(p.profitPerUnit),
                                style: display(context, size: 24, color: c.ok),
                              ),
                            ],
                          ),
                          const SizedBox(height: 4),
                          Row(
                            children: [
                              Expanded(
                                child: Text(
                                  'Lucro total da receita',
                                  style: TextStyle(color: c.ok, fontSize: 13),
                                ),
                              ),
                              Text(
                                money(p.profitTotal),
                                style: TextStyle(
                                  color: c.ok,
                                  fontWeight: FontWeight.w800,
                                ),
                              ),
                            ],
                          ),
                          Row(
                            children: [
                              Expanded(
                                child: Text(
                                  'Margem sobre o preço',
                                  style: TextStyle(color: c.ok, fontSize: 13),
                                ),
                              ),
                              Text(
                                percent(p.realMarginPct),
                                style: TextStyle(
                                  color: c.ok,
                                  fontWeight: FontWeight.w800,
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      'Para onde vai cada R\$ 1 do preço',
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                    const SizedBox(height: 8),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(99),
                      child: SizedBox(
                        height: 14,
                        child: Row(
                          children: [
                            for (final (v, col) in [
                              (p.ingredients, c.pink),
                              (p.labor, c.ink),
                              (p.overhead, c.warn),
                              (p.profitTotal < 0 ? 0.0 : p.profitTotal, c.ok),
                            ])
                              Expanded(
                                flex: (share(v) * 10).round().clamp(0, 10000),
                                child: Container(color: col),
                              ),
                            Expanded(
                              flex:
                                  ((100 -
                                                  [
                                                        p.ingredients,
                                                        p.labor,
                                                        p.overhead,
                                                        p.profitTotal < 0
                                                            ? 0.0
                                                            : p.profitTotal,
                                                      ]
                                                      .map(share)
                                                      .fold<double>(
                                                        0,
                                                        (a, b) => a + b,
                                                      ))
                                              .clamp(0, 100) *
                                          10)
                                      .round(),
                              child: Container(color: c.surface2),
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 14,
                      runSpacing: 4,
                      children: [
                        for (final (label, v, col) in [
                          ('Ingredientes', p.ingredients, c.pink),
                          ('Mão de obra', p.labor, c.ink),
                          ('Gastos rateados', p.overhead, c.warn),
                          (
                            'Lucro',
                            p.profitTotal < 0 ? 0.0 : p.profitTotal,
                            c.ok,
                          ),
                        ])
                          Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Container(
                                width: 10,
                                height: 10,
                                decoration: BoxDecoration(
                                  color: col,
                                  shape: BoxShape.circle,
                                ),
                              ),
                              const SizedBox(width: 6),
                              Text(
                                '$label ${share(v).round()}%',
                                style: TextStyle(
                                  color: c.muted,
                                  fontSize: 12.5,
                                ),
                              ),
                            ],
                          ),
                      ],
                    ),
                  ],
                ),
              ),
              if (_r != null) ...[
                const SizedBox(height: 16),
                TextButton.icon(
                  onPressed: _delete,
                  icon: const Icon(Icons.delete_outline_rounded),
                  label: const Text('Excluir receita'),
                  style: TextButton.styleFrom(foregroundColor: c.bad),
                ),
              ],
            ],
          ),
        ),
        SafeArea(
          top: false,
          child: Container(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
            decoration: BoxDecoration(
              color: c.surface,
              border: Border(top: BorderSide(color: c.line)),
            ),
            child: Row(
              children: [
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      'Preço sugerido',
                      style: TextStyle(color: c.muted, fontSize: 12),
                    ),
                    Text(
                      money(p.rounded),
                      style: display(context, size: 24, color: c.pinkStrong),
                    ),
                  ],
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: BusyButton(
                    label: 'Salvar receita',
                    busy: _busy,
                    onPressed: _name.text.trim().isEmpty ? null : _save,
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
