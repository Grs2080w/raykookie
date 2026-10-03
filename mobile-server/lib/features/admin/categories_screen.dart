import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/category_chip.dart';
import 'package:raykookie_common/ui/icons.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

const _kinds = [
  ('ingredient', 'Ingredientes'),
  ('recipe', 'Receitas'),
  ('expense', 'Gastos'),
];
const _colors = [
  '#d42a76',
  '#e5484d',
  '#f0a04b',
  '#d9a441',
  '#5f9d6e',
  '#2fa4a9',
  '#5b8def',
  '#9b6cf0',
  '#c2438a',
  '#8a5a44',
  '#7a8899',
];

class CategoriesScreen extends StatefulWidget {
  const CategoriesScreen({super.key});
  @override
  State<CategoriesScreen> createState() => _CategoriesScreenState();
}

class _CategoriesScreenState extends State<CategoriesScreen> {
  String _kind = 'ingredient';

  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    return Scaffold(
      appBar: AppBar(title: const Text('Categorias')),
      body: Loader<List<Category>>(
        load: () async => [
          for (final e in await api.get('/api/categories') as List)
            Category.fromJson(e),
        ],
        builder: (context, all, reload) {
          final c = context.c;
          final list = all.where((x) => x.kind == _kind).toList();
          Future<void> edit([Category? cat]) async {
            final saved = await showFormSheet<bool>(
              context,
              title: cat == null ? 'Nova categoria' : 'Editar categoria',
              child: _CategoryForm(existing: cat, kind: _kind),
            );
            if (saved == true) await reload();
          }

          Future<void> remove(Category cat) async {
            final msg = cat.uses > 0
                ? '${cat.uses} item(ns) usam esta categoria e ficarão sem categoria. Nada mais é apagado.'
                : 'Esta categoria não está em uso.';
            if (!await confirmDialog(
              context,
              title: 'Excluir “${cat.name}”?',
              message: msg,
            ))
              return;
            try {
              await api.delete('/api/categories/${cat.id}');
              Toast.ok('Categoria removida');
              await reload();
            } on ApiException catch (e) {
              Toast.error(e.message);
            }
          }

          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Organize ingredientes, receitas e gastos. Crie as suas, escolha um ícone e uma cor.',
              ),
              const SizedBox(height: 14),
              SegmentedButton<String>(
                segments: [
                  for (final k in _kinds)
                    ButtonSegment(value: k.$1, label: Text(k.$2)),
                ],
                selected: {_kind},
                onSelectionChanged: (s) => setState(() => _kind = s.first),
                showSelectedIcon: false,
                style: SegmentedButton.styleFrom(
                  selectedBackgroundColor: c.pink,
                  selectedForegroundColor: c.onPink,
                ),
              ),
              const SizedBox(height: 14),
              BusyButton(
                label: 'Nova categoria',
                icon: Icons.add_rounded,
                onPressed: () => edit(),
              ),
              const SizedBox(height: 14),
              if (list.isEmpty)
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(28),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(color: c.line),
                  ),
                  child: Text(
                    'Nenhuma categoria aqui ainda. Crie a primeira.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: c.muted),
                  ),
                )
              else
                for (final cat in list)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: AppCard(
                      padding: const EdgeInsets.fromLTRB(12, 10, 4, 10),
                      child: Row(
                        children: [
                          Container(
                            width: 48,
                            height: 48,
                            decoration: BoxDecoration(
                              color: hexColor(cat.color)
                                  .withValues(alpha: 0.16),
                              borderRadius: BorderRadius.circular(16),
                            ),
                            child: Icon(
                              categoryIcon(cat.icon),
                              color: hexColor(cat.color),
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  cat.name,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    fontWeight: FontWeight.w800,
                                    fontSize: 16,
                                  ),
                                ),
                                Text(
                                  cat.uses == 0
                                      ? 'Sem itens'
                                      : cat.uses == 1
                                      ? '1 item'
                                      : '${cat.uses} itens',
                                  style: TextStyle(
                                    color: c.muted,
                                    fontSize: 13,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          IconButton(
                            onPressed: () => edit(cat),
                            icon: const Icon(Icons.edit_outlined),
                            tooltip: 'Editar ${cat.name}',
                          ),
                          IconButton(
                            onPressed: () => remove(cat),
                            icon: Icon(
                              Icons.delete_outline_rounded,
                              color: c.bad,
                            ),
                            tooltip: 'Excluir ${cat.name}',
                          ),
                        ],
                      ),
                    ),
                  ),
            ],
          );
        },
      ),
    );
  }
}

class _CategoryForm extends StatefulWidget {
  const _CategoryForm({this.existing, required this.kind});
  final Category? existing;
  final String kind;
  @override
  State<_CategoryForm> createState() => _CategoryFormState();
}

class _CategoryFormState extends State<_CategoryForm> {
  late final _name = TextEditingController(text: widget.existing?.name ?? '');
  late String _icon = widget.existing?.icon ?? 'tag';
  late String _color = widget.existing?.color ?? _colors.first;
  bool _busy = false;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_name.text.trim().isEmpty)
      return Toast.error('Dê um nome para a categoria.');
    final api = context.read<Session>().ownerApi;
    setState(() => _busy = true);
    try {
      if (widget.existing == null) {
        await api.post('/api/categories', {
          'name': _name.text,
          'icon': _icon,
          'color': _color,
          'kind': widget.kind,
        });
      } else {
        await api.patch('/api/categories/${widget.existing!.id}', {
          'name': _name.text,
          'icon': _icon,
          'color': _color,
        });
      }
      Toast.ok(
        widget.existing == null ? 'Categoria criada' : 'Categoria atualizada',
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
    final col = hexColor(_color);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: c.surface2,
            borderRadius: BorderRadius.circular(18),
          ),
          child: Row(
            children: [
              Container(
                width: 52,
                height: 52,
                decoration: BoxDecoration(
                  color: col.withValues(alpha: 0.18),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Icon(categoryIcon(_icon), color: col, size: 26),
              ),
              const SizedBox(width: 14),
              Flexible(
                child: CategoryChip(
                  name: _name.text.isEmpty ? 'Nome da categoria' : _name.text,
                  icon: _icon,
                  color: _color,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        AppTextField(
          label: 'Nome',
          controller: _name,
          maxLength: 40,
          hint: 'Ex.: Massas folhadas',
          onChanged: (_) => setState(() {}),
          capitalization: TextCapitalization.sentences,
        ),
        const SizedBox(height: 16),
        const Text('Cor', style: TextStyle(fontWeight: FontWeight.w600)),
        const SizedBox(height: 8),
        Wrap(
          spacing: 10,
          runSpacing: 10,
          children: [
            for (final hex in _colors)
              GestureDetector(
                onTap: () => setState(() => _color = hex),
                child: Container(
                  width: 34,
                  height: 34,
                  decoration: BoxDecoration(
                    color: hexColor(hex),
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: _color == hex ? c.ink : Colors.transparent,
                      width: 2.5,
                    ),
                  ),
                  child: Semantics(label: 'Cor $hex', selected: _color == hex),
                ),
              ),
          ],
        ),
        const SizedBox(height: 16),
        const Text('Ícone', style: TextStyle(fontWeight: FontWeight.w600)),
        const SizedBox(height: 8),
        SizedBox(
          height: 210,
          child: GridView.count(
            crossAxisCount: 7,
            mainAxisSpacing: 6,
            crossAxisSpacing: 6,
            children: [
              for (final name in categoryIcons.keys)
                GestureDetector(
                  onTap: () => setState(() => _icon = name),
                  child: Container(
                    decoration: BoxDecoration(
                      color: _icon == name ? c.pinkTint : Colors.transparent,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(
                        color: _icon == name ? c.pink : Colors.transparent,
                      ),
                    ),
                    child: Icon(
                      categoryIcon(name),
                      size: 22,
                      color: _icon == name ? c.pinkStrong : c.muted,
                      semanticLabel: name,
                    ),
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(height: 16),
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
              child: BusyButton(
                label: 'Salvar',
                busy: _busy,
                onPressed: _name.text.trim().isEmpty ? null : _save,
              ),
            ),
          ],
        ),
      ],
    );
  }
}
