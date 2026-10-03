import 'package:flutter/material.dart';
import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

import '../../core/receipt.dart';

enum Choice { add, create, skip, pending }

class _Line {
  _Line(this.item)
    : create = item.create,
      choice = item.match?.exact == true ? Choice.add : Choice.pending;
  final ReceiptItem item;
  CreatePlan create;
  Choice choice;
  bool get exact => item.match?.exact == true;
  bool get suggested => item.match != null && !exact;
  bool get isNew => item.match == null;
}

/// Confere o que a nota trouxe: o que já existe no estoque é somado; o que é novo a confeiteira aceita ou recusa.
class ReceiptReview extends StatefulWidget {
  const ReceiptReview({
    super.key,
    required this.preview,
    required this.service,
    required this.onDone,
  });
  final ReceiptPreview preview;
  final ReceiptService service;
  final VoidCallback onDone;
  @override
  State<ReceiptReview> createState() => _ReceiptReviewState();
}

class _ReceiptReviewState extends State<ReceiptReview> {
  late final List<_Line> _lines = [
    for (final i in widget.preview.items) _Line(i),
  ];
  bool _updatePrice = true;
  bool _busy = false;

  int get _pending => _lines.where((l) => l.choice == Choice.pending).length;
  int get _selected => _lines
      .where((l) => l.choice == Choice.add || l.choice == Choice.create)
      .length;
  bool get _already => widget.preview.alreadyImported != null;

  void _setAll(bool newOnes, Choice c) => setState(() {
    for (final l in _lines.where(
      (l) => l.choice == Choice.pending && (l.isNew || l.suggested),
    )) {
      l.choice = c;
    }
  });

  Future<void> _edit(_Line l) async {
    final r = await showFormSheet<CreatePlan>(
      context,
      title: 'Ajustar ingrediente novo',
      child: _CreateEditor(plan: l.create),
    );
    if (r != null)
      setState(() {
        l.create = r;
        l.choice = Choice.create;
      });
  }

  Future<void> _confirm() async {
    final items = <Map<String, Object?>>[];
    for (final l in _lines) {
      if (l.choice == Choice.add &&
          l.item.match != null &&
          l.item.add != null) {
        items.add({
          'action': 'add',
          'ingredient_id': l.item.match!.ingredientId,
          'add_qty': l.item.add!.addQty,
          'package_cost': _updatePrice ? l.item.add!.packageCost : null,
          'item_name': l.item.name,
        });
      } else if (l.choice == Choice.create) {
        items.add(l.create.toJson());
      }
    }
    setState(() => _busy = true);
    try {
      final r = await widget.service.import(widget.preview, items);
      final parts = [
        if (r.added > 0)
          '${r.added} ${r.added == 1 ? 'item somado' : 'itens somados'}',
        if (r.created > 0)
          '${r.created} ${r.created == 1 ? 'ingrediente novo' : 'ingredientes novos'}',
      ];
      Toast.ok('${parts.join(' e ')} no estoque');
      widget.onDone();
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final p = widget.preview;
    final adds = _lines.where((l) => l.exact).toList();
    final questions = _lines.where((l) => !l.exact).toList();
    final canConfirm = !_already && !_busy && _pending == 0 && _selected > 0;
    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
            children: [
              AppCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      p.store.isEmpty ? 'Nota fiscal' : p.store,
                      style: display(context, size: 24),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      [
                        if (p.date != null) _date(p.date!),
                        '${p.items.length} ${p.items.length == 1 ? 'item' : 'itens'}',
                        if (p.total != null) 'total ${money(p.total!)}',
                      ].join(' · '),
                      style: TextStyle(color: c.muted),
                    ),
                  ],
                ),
              ),
              if (_already) ...[
                const SizedBox(height: 10),
                InfoBanner(
                  'Essa nota já foi lançada no estoque. Para não somar duas vezes, ela não pode ser lançada de novo.',
                  icon: Icons.warning_amber_rounded,
                  tone: c.warn,
                ),
              ],
              if (adds.isNotEmpty) ...[
                const SectionTitle('Somar ao estoque'),
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    'Itens que já estão no seu estoque. Desmarque o que não quiser somar.',
                    style: TextStyle(color: c.muted),
                  ),
                ),
                for (final l in adds) _addCard(context, l),
                AppCard(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 2,
                  ),
                  child: SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text(
                      'Atualizar o preço das embalagens',
                      style: TextStyle(fontWeight: FontWeight.w700),
                    ),
                    subtitle: Text(
                      'Usa o preço pago nesta nota no custo das receitas.',
                      style: TextStyle(color: c.muted, fontSize: 13),
                    ),
                    value: _updatePrice,
                    activeTrackColor: c.pink,
                    onChanged: (v) => setState(() => _updatePrice = v),
                  ),
                ),
              ],
              if (questions.isNotEmpty) ...[
                SectionTitle(
                  'Não estão no estoque${_pending > 0 ? ' ($_pending)' : ''}',
                ),
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    'Quer adicionar esses itens ao estoque? Toque em Aceitar ou Recusar em cada um.',
                    style: TextStyle(color: c.muted),
                  ),
                ),
                if (_pending > 1)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: Row(
                      children: [
                        Expanded(
                          child: BusyButton(
                            label: 'Aceitar todos',
                            outlined: true,
                            onPressed: () => _setAll(true, Choice.create),
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: BusyButton(
                            label: 'Recusar todos',
                            outlined: true,
                            onPressed: () => _setAll(true, Choice.skip),
                          ),
                        ),
                      ],
                    ),
                  ),
                for (final l in questions) _questionCard(context, l),
              ],
            ],
          ),
        ),
        SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
            child: BusyButton(
              label: _already
                  ? 'Nota já lançada'
                  : _pending > 0
                  ? 'Responda os itens novos ($_pending)'
                  : _selected == 0
                  ? 'Nenhum item selecionado'
                  : 'Lançar no estoque ($_selected)',
              icon: Icons.inventory_2_outlined,
              busy: _busy,
              onPressed: canConfirm ? _confirm : null,
            ),
          ),
        ),
      ],
    );
  }

  String _date(String iso) => iso.length == 10
      ? '${iso.substring(8)}/${iso.substring(5, 7)}/${iso.substring(0, 4)}'
      : iso;

  Widget _addCard(BuildContext context, _Line l) {
    final c = context.c;
    final it = l.item;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: AppCard(
        padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    it.match!.name,
                    style: const TextStyle(
                      fontWeight: FontWeight.w800,
                      fontSize: 16,
                    ),
                  ),
                  Text(
                    'na nota: ${it.name}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(color: c.muted, fontSize: 12.5),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '+${number(it.add!.addQty)} ${it.add!.addQty == 1 ? 'embalagem' : 'embalagens'} · ${money(it.add!.packageCost)} cada',
                    style: TextStyle(color: c.ink, fontSize: 13.5),
                  ),
                  if (it.add!.note.isNotEmpty && it.add!.note.contains('=') ||
                      it.add!.note.contains('≈'))
                    Text(
                      it.add!.note,
                      style: TextStyle(color: c.muted, fontSize: 12),
                    ),
                ],
              ),
            ),
            Switch(
              value: l.choice == Choice.add,
              activeTrackColor: c.pink,
              onChanged: (v) =>
                  setState(() => l.choice = v ? Choice.add : Choice.skip),
            ),
          ],
        ),
      ),
    );
  }

  Widget _questionCard(BuildContext context, _Line l) {
    final c = context.c;
    final it = l.item;
    final cp = l.create;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: AppCard(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              it.name,
              style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
            ),
            const SizedBox(height: 2),
            Text(
              '${number(it.qty)} ${it.unit.toLowerCase()} · ${money(it.unitPrice)} cada · total ${money(it.total)}',
              style: TextStyle(color: c.muted, fontSize: 13),
            ),
            const SizedBox(height: 10),
            if (l.suggested) ...[
              Text(
                'Parece ser o “${it.match!.name}” que você já tem. Somar lá?',
                style: TextStyle(
                  color: c.pinkStrong,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  ChoiceChip(
                    label: Text('Somar em ${it.match!.name}'),
                    selected: l.choice == Choice.add,
                    onSelected: (_) => setState(() => l.choice = Choice.add),
                  ),
                  ChoiceChip(
                    label: const Text('É um item novo'),
                    selected: l.choice == Choice.create,
                    onSelected: (_) => setState(() => l.choice = Choice.create),
                  ),
                  ChoiceChip(
                    label: const Text('Ignorar'),
                    selected: l.choice == Choice.skip,
                    onSelected: (_) => setState(() => l.choice = Choice.skip),
                  ),
                ],
              ),
              if (l.choice == Choice.add && it.add != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    '+${number(it.add!.addQty)} ${it.add!.addQty == 1 ? 'embalagem' : 'embalagens'} · ${money(it.add!.packageCost)} cada',
                    style: TextStyle(color: c.muted, fontSize: 13),
                  ),
                ),
            ] else ...[
              Text(
                'Novo no estoque: “${cp.name}” · ${number(cp.packageSize)} ${cp.unit} por embalagem · ${money(cp.packageCost)} · ${number(cp.stockQty)} ${cp.stockQty == 1 ? 'embalagem' : 'embalagens'}',
                style: TextStyle(color: c.ink, fontSize: 13.5),
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  ChoiceChip(
                    label: const Text('Aceitar'),
                    selected: l.choice == Choice.create,
                    onSelected: (_) => setState(() => l.choice = Choice.create),
                  ),
                  const SizedBox(width: 8),
                  ChoiceChip(
                    label: const Text('Recusar'),
                    selected: l.choice == Choice.skip,
                    onSelected: (_) => setState(() => l.choice = Choice.skip),
                  ),
                  const Spacer(),
                  TextButton.icon(
                    onPressed: () => _edit(l),
                    icon: const Icon(Icons.edit_outlined, size: 18),
                    label: const Text('Ajustar'),
                  ),
                ],
              ),
            ],
            if (l.suggested && l.choice == Choice.create)
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(
                  onPressed: () => _edit(l),
                  icon: const Icon(Icons.edit_outlined, size: 18),
                  label: const Text('Ajustar'),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _CreateEditor extends StatefulWidget {
  const _CreateEditor({required this.plan});
  final CreatePlan plan;
  @override
  State<_CreateEditor> createState() => _CreateEditorState();
}

class _CreateEditorState extends State<_CreateEditor> {
  late final _name = TextEditingController(text: widget.plan.name);
  late final _size = TextEditingController(
    text: fieldNum(widget.plan.packageSize),
  );
  late final _cost = TextEditingController(
    text: fieldNum(widget.plan.packageCost),
  );
  late final _qty = TextEditingController(text: fieldNum(widget.plan.stockQty));
  late String _unit = widget.plan.unit;

  @override
  void dispose() {
    for (final c in [_name, _size, _cost, _qty]) {
      c.dispose();
    }
    super.dispose();
  }

  void _ok() {
    if (_name.text.trim().isEmpty)
      return Toast.error('Informe o nome do ingrediente.');
    if (parseNum(_size.text) <= 0)
      return Toast.error('O tamanho da embalagem precisa ser maior que zero.');
    Navigator.pop(
      context,
      widget.plan.copy(
        name: _name.text.trim(),
        unit: _unit,
        packageSize: parseNum(_size.text),
        packageCost: parseNum(_cost.text),
        stockQty: parseNum(_qty.text),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => Column(
    children: [
      AppTextField(
        label: 'Nome',
        controller: _name,
        maxLength: 80,
        capitalization: TextCapitalization.sentences,
      ),
      const SizedBox(height: 14),
      SelectField<String>(
        label: 'Unidade',
        value: _unit,
        items: const [
          DropdownMenuItem(value: 'g', child: Text('Gramas (g)')),
          DropdownMenuItem(value: 'ml', child: Text('Mililitros (ml)')),
          DropdownMenuItem(value: 'un', child: Text('Unidades (un)')),
        ],
        onChanged: (v) => setState(() => _unit = v ?? 'g'),
      ),
      const SizedBox(height: 14),
      Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: NumberField(label: 'Embalagem ($_unit)', controller: _size),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: NumberField(label: 'Preço (R\$)', controller: _cost),
          ),
        ],
      ),
      const SizedBox(height: 14),
      NumberField(label: 'Embalagens compradas', controller: _qty),
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
            child: BusyButton(label: 'Usar', onPressed: _ok),
          ),
        ],
      ),
    ],
  );
}
