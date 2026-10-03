import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/category_chip.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

class _CostsData {
  _CostsData(this.settings, this.costs, this.categories);
  final Settings settings;
  final List<FixedCost> costs;
  final List<Category> categories;
}

/// Custos e ajustes: valor da hora, unidades por mês e gastos do mês (entram no preço de todas as receitas).
class CostsScreen extends StatelessWidget {
  const CostsScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    return Scaffold(
      appBar: AppBar(title: const Text('Custos e ajustes')),
      body: Loader<_CostsData>(
        load: () async {
          final r = await Future.wait([
            api.get('/api/settings'),
            api.get('/api/fixed-costs'),
            api.get('/api/categories?kind=expense'),
          ]);
          return _CostsData(
            Settings.fromJson(r[0] as Map<String, dynamic>),
            [for (final e in r[1] as List) FixedCost.fromJson(e)],
            [for (final e in r[2] as List) Category.fromJson(e)],
          );
        },
        builder: (context, d, reload) => _CostsView(data: d, reload: reload),
      ),
    );
  }
}

class _CostsView extends StatefulWidget {
  const _CostsView({required this.data, required this.reload});
  final _CostsData data;
  final Future<void> Function() reload;
  @override
  State<_CostsView> createState() => _CostsViewState();
}

class _CostsViewState extends State<_CostsView> {
  late final _rate = TextEditingController(
    text: widget.data.settings.hourlyRate > 0
        ? fieldNum(widget.data.settings.hourlyRate)
        : '',
  );
  late final _units = TextEditingController(
    text: fieldNum(widget.data.settings.monthlyUnits),
  );
  late final _minMargin = TextEditingController(
    text: fieldNum(widget.data.settings.minMarginPct),
  );
  bool _busy = false;

  @override
  void dispose() {
    _rate.dispose();
    _units.dispose();
    _minMargin.dispose();
    super.dispose();
  }

  Future<void> _saveSettings() async {
    setState(() => _busy = true);
    try {
      await context.read<Session>().ownerApi.put('/api/settings', {
        'hourly_rate': _rate.text,
        'monthly_units': _units.text,
        'min_margin_pct': _minMargin.text,
      });
      Toast.ok('Ajustes salvos');
      await widget.reload();
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _edit([FixedCost? cost]) async {
    final saved = await showFormSheet<bool>(
      context,
      title: cost == null ? 'Novo gasto' : 'Editar gasto',
      child: _CostForm(existing: cost, categories: widget.data.categories),
    );
    if (saved == true) await widget.reload();
  }

  Future<void> _delete(FixedCost cost) async {
    final api = context.read<Session>().ownerApi;
    if (!await confirmDialog(
      context,
      title: 'Remover “${cost.name}”?',
      message:
          'O rateio por unidade e o preço das receitas serão recalculados.',
      confirmLabel: 'Remover',
    ))
      return;
    try {
      await api.delete('/api/fixed-costs/${cost.id}');
      Toast.ok('Gasto removido');
      await widget.reload();
    } on ApiException catch (e) {
      Toast.error(e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final costs = widget.data.costs;
    final cats = {for (final x in widget.data.categories) x.id: x};
    final total = costs.fold<double>(0, (s, x) => s + x.amount);
    final units = parseNum(_units.text);
    final perUnit = units > 0 ? total / units : 0.0;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'O valor da hora e os gastos do mês entram no preço de todas as receitas.',
        ),
        const SizedBox(height: 16),
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Base do cálculo', style: display(context, size: 22)),
              const SizedBox(height: 14),
              NumberField(
                label: 'Valor da sua hora de trabalho (R\$)',
                controller: _rate,
                hint: 'Ex.: 20',
                helper: 'Multiplicado pelo tempo de produção de cada receita.',
                onChanged: (_) => setState(() {}),
              ),
              const SizedBox(height: 14),
              NumberField(
                label: 'Margem mínima (%)',
                controller: _minMargin,
                helper: 'Se a margem de uma receita com preço fixado cair abaixo disso, você recebe um alerta.',
                onChanged: (_) => setState(() {}),
              ),
              const SizedBox(height: 14),
              NumberField(
                label: 'Unidades produzidas por mês',
                controller: _units,
                helper:
                    'Estimativa. Divide os gastos do mês entre as unidades.',
                onChanged: (_) => setState(() {}),
              ),
              const SizedBox(height: 16),
              BusyButton(
                label: 'Salvar ajustes',
                busy: _busy,
                onPressed: _saveSettings,
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        AppCard(
          color: c.pinkTint,
          borderColor: c.pinkTint,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Rateio por unidade produzida',
                style: TextStyle(
                  color: c.pinkStrong,
                  fontWeight: FontWeight.w600,
                ),
              ),
              Text(
                moneyPrecise(perUnit),
                style: display(context, size: 38, color: c.pinkStrong),
              ),
              const SizedBox(height: 4),
              Text(
                '${money(total)} ÷ ${number(units)} unidades. Esse valor entra no custo de cada unidade de toda receita.',
                style: TextStyle(
                  color: c.pinkStrong.withValues(alpha: 0.85),
                  fontSize: 13,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      'Gastos fixos e variáveis do mês',
                      style: display(context, size: 20),
                    ),
                  ),
                  FilledButton.icon(
                    onPressed: () => _edit(),
                    icon: const Icon(Icons.add_rounded, size: 18),
                    label: const Text('Adicionar'),
                    style: FilledButton.styleFrom(
                      minimumSize: const Size(0, 40),
                      padding: const EdgeInsets.symmetric(horizontal: 14),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              if (costs.isEmpty)
                Container(
                  width: double.infinity,
                  margin: const EdgeInsets.only(top: 8),
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(18),
                    border: Border.all(color: c.line),
                  ),
                  child: Text(
                    'Nenhum gasto ainda. Some energia, gás, internet, embalagens de uso geral… O total é dividido entre as unidades que você produz.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: c.muted, fontSize: 13.5),
                  ),
                )
              else
                for (final x in costs)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                x.name,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Wrap(
                                spacing: 8,
                                children: [
                                  if (cats[x.categoryId] != null)
                                    CategoryChip(
                                      name: cats[x.categoryId]!.name,
                                      icon: cats[x.categoryId]!.icon,
                                      color: cats[x.categoryId]!.color,
                                    ),
                                  AppBadge(
                                    x.kind == 'fixed' ? 'Fixo' : 'Variável',
                                    dot: false,
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                        Text(
                          money(x.amount),
                          style: const TextStyle(fontWeight: FontWeight.w800),
                        ),
                        IconButton(
                          onPressed: () => _edit(x),
                          icon: const Icon(Icons.edit_outlined, size: 20),
                          tooltip: 'Editar ${x.name}',
                        ),
                        IconButton(
                          onPressed: () => _delete(x),
                          icon: Icon(
                            Icons.delete_outline_rounded,
                            size: 20,
                            color: c.bad,
                          ),
                          tooltip: 'Excluir ${x.name}',
                        ),
                      ],
                    ),
                  ),
              const Divider(height: 24),
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      'Total por mês',
                      style: TextStyle(fontWeight: FontWeight.w700),
                    ),
                  ),
                  Text(money(total), style: display(context, size: 24)),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _CostForm extends StatefulWidget {
  const _CostForm({this.existing, required this.categories});
  final FixedCost? existing;
  final List<Category> categories;
  @override
  State<_CostForm> createState() => _CostFormState();
}

class _CostFormState extends State<_CostForm> {
  late final _name = TextEditingController(text: widget.existing?.name ?? '');
  late final _amount = TextEditingController(
    text: widget.existing == null ? '' : fieldNum(widget.existing!.amount),
  );
  late int? _category = widget.existing?.categoryId;
  late String _kind = widget.existing?.kind ?? 'fixed';
  bool _busy = false;

  @override
  void dispose() {
    _name.dispose();
    _amount.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_name.text.trim().isEmpty)
      return Toast.error('Dê um nome para o gasto.');
    final body = {
      'name': _name.text,
      'category_id': _category?.toString() ?? '',
      'kind': _kind,
      'amount': _amount.text,
    };
    final api = context.read<Session>().ownerApi;
    setState(() => _busy = true);
    try {
      if (widget.existing == null) {
        await api.post('/api/fixed-costs', body);
      } else {
        await api.patch('/api/fixed-costs/${widget.existing!.id}', body);
      }
      Toast.ok(
        widget.existing == null ? 'Gasto adicionado' : 'Gasto atualizado',
      );
      if (mounted) Navigator.pop(context, true);
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Column(
    children: [
      AppTextField(
        label: 'Nome',
        controller: _name,
        maxLength: 80,
        hint: 'Ex.: Conta de luz',
        capitalization: TextCapitalization.sentences,
      ),
      const SizedBox(height: 14),
      SelectField<int?>(
        label: 'Categoria',
        value: _category,
        items: [
          const DropdownMenuItem(value: null, child: Text('Sem categoria')),
          for (final x in widget.categories)
            DropdownMenuItem(
              value: x.id,
              child: Text(x.name, overflow: TextOverflow.ellipsis),
            ),
        ],
        onChanged: (v) => setState(() => _category = v),
      ),
      const SizedBox(height: 14),
      SelectField<String>(
        label: 'Tipo',
        value: _kind,
        items: const [
          DropdownMenuItem(
            value: 'fixed',
            child: Text('Fixo (mesmo valor todo mês)'),
          ),
          DropdownMenuItem(
            value: 'variable',
            child: Text('Variável (média do mês)'),
          ),
        ],
        onChanged: (v) => setState(() => _kind = v ?? 'fixed'),
      ),
      const SizedBox(height: 14),
      NumberField(label: 'Valor por mês (R\$)', controller: _amount),
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
